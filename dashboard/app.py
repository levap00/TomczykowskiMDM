import json
import secrets
from datetime import datetime, timedelta
from functools import wraps

from flask import Flask, request, jsonify, render_template, redirect, url_for, flash
from flask_sqlalchemy import SQLAlchemy

app = Flask(__name__)
app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///mdm.db"
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
app.secret_key = secrets.token_hex(16)

db = SQLAlchemy(app)


# ─── Models ───────────────────────────────────────────────────────────────────

class Device(db.Model):
    __tablename__ = "devices"
    id = db.Column(db.String(64), primary_key=True)
    token = db.Column(db.String(64), nullable=False)
    registered_at = db.Column(db.DateTime, default=datetime.utcnow)
    last_seen = db.Column(db.DateTime)
    android_version = db.Column(db.String(16))
    battery = db.Column(db.Integer)
    rooted = db.Column(db.Boolean, default=False)
    lat = db.Column(db.Float)
    lon = db.Column(db.Float)
    location_accuracy = db.Column(db.Float)

    command = db.relationship("Command", backref="device", uselist=False, cascade="all, delete-orphan")
    install_reports = db.relationship("InstallReport", backref="device", cascade="all, delete-orphan")

    @property
    def is_online(self):
        if not self.last_seen:
            return False
        return datetime.utcnow() - self.last_seen < timedelta(minutes=2)


class Command(db.Model):
    __tablename__ = "commands"
    device_id = db.Column(db.String(64), db.ForeignKey("devices.id"), primary_key=True)
    lock = db.Column(db.Boolean, default=False)
    blocked = db.Column(db.Boolean, default=False)
    disable_camera = db.Column(db.Boolean, default=False)
    wipe = db.Column(db.Boolean, default=False)
    alarm = db.Column(db.Boolean, default=False)
    alarm_interval_sec = db.Column(db.Integer, default=12)
    disable_tethering = db.Column(db.Boolean, default=False)
    install_apk_url = db.Column(db.String(512))
    install_apk_checksum = db.Column(db.String(64))
    blocked_apps = db.Column(db.Text)  # JSON array stored as text
    geofence_lat = db.Column(db.Float)
    geofence_lon = db.Column(db.Float)
    geofence_radius = db.Column(db.Float)
    command_version = db.Column(db.Integer, default=0)
    updated_at = db.Column(db.DateTime)

    def to_api_json(self):
        geofence = None
        if self.geofence_lat is not None and self.geofence_lon is not None:
            geofence = {
                "lat": self.geofence_lat,
                "lon": self.geofence_lon,
                "radius": self.geofence_radius or 100.0,
            }

        blocked_apps = None
        if self.blocked_apps:
            try:
                blocked_apps = json.loads(self.blocked_apps)
            except Exception:
                pass

        return {
            "lock": bool(self.lock),
            "blocked": bool(self.blocked),
            "disable_camera": bool(self.disable_camera),
            "wipe": bool(self.wipe),
            "run_diagnostics": False,
            "alarm": bool(self.alarm),
            "alarm_interval_sec": self.alarm_interval_sec or 12,
            "disable_tethering": bool(self.disable_tethering),
            "install_apk_url": self.install_apk_url or None,
            "install_apk_checksum": self.install_apk_checksum or None,
            "blocked_apps": blocked_apps,
            "geofence": geofence,
            "command_version": self.command_version or 0,
        }


class InstallReport(db.Model):
    __tablename__ = "install_reports"
    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    device_id = db.Column(db.String(64), db.ForeignKey("devices.id"))
    apk_url = db.Column(db.String(512))
    checksum = db.Column(db.String(64))
    success = db.Column(db.Boolean)
    message = db.Column(db.Text)
    reported_at = db.Column(db.DateTime, default=datetime.utcnow)


# ─── Jinja2 filters ───────────────────────────────────────────────────────────

@app.template_filter("from_json")
def from_json_filter(value):
    if not value:
        return []
    try:
        return json.loads(value)
    except Exception:
        return []


# ─── Auth helper ──────────────────────────────────────────────────────────────

def require_token(f):
    @wraps(f)
    def decorated(device_id, *args, **kwargs):
        token = request.headers.get("X-Auth-Token")
        device = db.session.get(Device, device_id)
        if not device or device.token != token:
            return jsonify({"error": "Unauthorized"}), 401
        return f(device_id, *args, **kwargs)
    return decorated


# ─── Device API (consumed by the Android app) ─────────────────────────────────

@app.route("/device/<device_id>/register", methods=["POST"])
def register(device_id):
    data = request.get_json(silent=True) or {}
    device = db.session.get(Device, device_id)
    if device:
        device.token = secrets.token_hex(32)
        device.last_seen = datetime.utcnow()
        _apply_telemetry(device, data)
    else:
        device = Device(
            id=device_id,
            token=secrets.token_hex(32),
            registered_at=datetime.utcnow(),
            last_seen=datetime.utcnow(),
        )
        _apply_telemetry(device, data)
        db.session.add(device)
        db.session.flush()
        cmd = Command(device_id=device_id, command_version=0, updated_at=datetime.utcnow())
        db.session.add(cmd)
    db.session.commit()
    return jsonify({"token": device.token})


@app.route("/device/<device_id>/report", methods=["POST"])
@require_token
def report(device_id):
    data = request.get_json(silent=True) or {}
    device = db.session.get(Device, device_id)
    device.last_seen = datetime.utcnow()
    _apply_telemetry(device, data)
    db.session.commit()
    return jsonify({"ok": True})


@app.route("/device/<device_id>/command", methods=["GET"])
@require_token
def get_command(device_id):
    device = db.session.get(Device, device_id)
    device.last_seen = datetime.utcnow()

    if not device.command:
        cmd = Command(device_id=device_id, command_version=0, updated_at=datetime.utcnow())
        db.session.add(cmd)
        db.session.flush()

    db.session.commit()
    return jsonify(device.command.to_api_json())


@app.route("/device/<device_id>/report-install", methods=["POST"])
@require_token
def report_install(device_id):
    data = request.get_json(silent=True) or {}
    rep = InstallReport(
        device_id=device_id,
        apk_url=data.get("install_apk_url"),
        checksum=data.get("checksum"),
        success=bool(data.get("success", False)),
        message=data.get("message", ""),
        reported_at=datetime.utcnow(),
    )
    db.session.add(rep)

    # Auto-clear install command after successful install
    device = db.session.get(Device, device_id)
    if data.get("success") and device.command:
        device.command.install_apk_url = None
        device.command.install_apk_checksum = None
        device.command.command_version += 1
        device.command.updated_at = datetime.utcnow()

    db.session.commit()
    return jsonify({"ok": True})


# ─── Dashboard routes (browser) ───────────────────────────────────────────────

@app.route("/")
def index():
    devices = Device.query.order_by(Device.last_seen.desc()).all()
    return render_template("index.html", devices=devices)


@app.route("/device/<device_id>")
def device_detail(device_id):
    device = db.get_or_404(Device, device_id)
    reports = (
        InstallReport.query
        .filter_by(device_id=device_id)
        .order_by(InstallReport.reported_at.desc())
        .limit(20)
        .all()
    )
    return render_template("device.html", device=device, reports=reports)


@app.route("/device/<device_id>/set-command", methods=["POST"])
def set_command(device_id):
    device = db.get_or_404(Device, device_id)
    cmd = device.command
    if not cmd:
        cmd = Command(device_id=device_id, command_version=0)
        db.session.add(cmd)
        db.session.flush()

    f = request.form
    cmd.lock = "lock" in f
    cmd.blocked = "blocked" in f
    cmd.disable_camera = "disable_camera" in f
    cmd.wipe = "wipe" in f
    cmd.alarm = "alarm" in f
    cmd.disable_tethering = "disable_tethering" in f

    try:
        cmd.alarm_interval_sec = int(f.get("alarm_interval_sec") or 12)
    except ValueError:
        cmd.alarm_interval_sec = 12

    apk_url = f.get("install_apk_url", "").strip()
    apk_checksum = f.get("install_apk_checksum", "").strip()
    cmd.install_apk_url = apk_url or None
    cmd.install_apk_checksum = apk_checksum or None

    blocked_apps_raw = f.get("blocked_apps", "").strip()
    if blocked_apps_raw:
        apps = [a.strip() for a in blocked_apps_raw.splitlines() if a.strip()]
        cmd.blocked_apps = json.dumps(apps) if apps else None
    else:
        cmd.blocked_apps = None

    geo_lat = f.get("geofence_lat", "").strip()
    geo_lon = f.get("geofence_lon", "").strip()
    geo_rad = f.get("geofence_radius", "").strip()
    if geo_lat and geo_lon:
        try:
            cmd.geofence_lat = float(geo_lat)
            cmd.geofence_lon = float(geo_lon)
            cmd.geofence_radius = float(geo_rad) if geo_rad else 100.0
        except ValueError:
            pass
    else:
        cmd.geofence_lat = None
        cmd.geofence_lon = None
        cmd.geofence_radius = None

    cmd.command_version = (cmd.command_version or 0) + 1
    cmd.updated_at = datetime.utcnow()
    db.session.commit()

    flash("Komenda zastosowana (wersja {})".format(cmd.command_version), "success")
    return redirect(url_for("device_detail", device_id=device_id))


@app.route("/device/<device_id>/delete", methods=["POST"])
def delete_device(device_id):
    device = db.get_or_404(Device, device_id)
    db.session.delete(device)
    db.session.commit()
    flash("Urządzenie usunięte", "warning")
    return redirect(url_for("index"))


# ─── Helpers ──────────────────────────────────────────────────────────────────

def _apply_telemetry(device, data):
    if "android_version" in data:
        device.android_version = str(data["android_version"])
    if "battery" in data and data["battery"] is not None:
        device.battery = int(data["battery"])
    if "rooted" in data:
        device.rooted = bool(data["rooted"])
    loc = data.get("location")
    if loc and isinstance(loc, dict):
        device.lat = loc.get("lat")
        device.lon = loc.get("lon")
        device.location_accuracy = loc.get("accuracy")


if __name__ == "__main__":
    with app.app_context():
        db.create_all()
    app.run(host="0.0.0.0", port=5000, debug=True)
