"""Kia Connect operations called by the Android app's serial worker."""

import json
import logging
from datetime import UTC, datetime

from hyundai_kia_connect_api import ClimateRequestOptions, Token, VehicleManager
from hyundai_kia_connect_api.ApiImpl import OTPRequest
from hyundai_kia_connect_api.const import OTP_NOTIFY_TYPE, TEMPERATURE_C, TEMPERATURE_F
from hyundai_kia_connect_api.utils import get_child_value

logging.disable(logging.CRITICAL)
_pending = None
_selection = None


def _result(**values):
    return json.dumps(values)


def _link_result(manager):
    global _pending, _selection
    _pending = None
    cars = list(manager.vehicles.values())
    if not cars:
        return _result(error="Dit Kia-account heeft geen gekoppelde auto.")
    if len(cars) == 1:
        return _linked_vehicle(manager, cars[0])
    _selection = manager, cars
    labels = []
    for index, car in enumerate(cars, 1):
        label = car.model or car.name or "Kia"
        suffix = str(car.VIN or "")[-4:]
        labels.append(f"{index}. {label} · VIN …{suffix}" if suffix else f"{index}. {label}")
    return _result(vehicles=labels)


def _linked_vehicle(manager, car):
    return _result(model=car.model or car.name or "Kia", session={
        "token": manager.token.to_dict(),
        "vehicle": car.id,
    })


def select_vehicle(index):
    global _selection
    if _selection is None:
        return _result(error="De voertuigkeuze is verlopen. Meld je opnieuw aan.")
    manager, cars = _selection
    _selection = None
    if not 0 <= index < len(cars):
        return _result(error="Ongeldige voertuigkeuze. Meld je opnieuw aan.")
    return _linked_vehicle(manager, cars[index])


def login(username, password, pin):
    global _pending
    clear_login()
    manager = VehicleManager(1, 1, username, password, pin, language="nl")
    response = manager.login()
    if response is True:
        return _link_result(manager)
    if isinstance(response, OTPRequest):
        _pending = manager
        return _result(email=bool(response.has_email), sms=bool(response.has_sms))
    return _result(error="Kia heeft geen sessie geopend.")


def send_code(channel):
    _pending.send_otp(OTP_NOTIFY_TYPE(channel))


def verify_code(code):
    _pending.verify_otp_and_complete_login(code)
    return _link_result(_pending)


def clear_login():
    global _pending, _selection
    _pending = None
    _selection = None


def prepare_start(saved):
    data = json.loads(saved)
    token = Token.from_dict(data["token"])
    manager = VehicleManager(
        1, 1, token.username, token.password, token.pin or "", token=token, language="nl",
    )
    manager.check_and_refresh_token()
    vehicle = data["vehicle"]
    if vehicle not in manager.vehicles:
        raise ValueError("The linked vehicle is missing")
    return manager, vehicle, _result(token=manager.token.to_dict(), vehicle=vehicle)


def start(prepared, temperature, duration, wheel, mirrors, windshield):
    manager, vehicle, _ = prepared
    # EU legacy uses code 1 for the combined group; CCS2 uses separate flags.
    heating = {(False, False): 0, (False, True): 2, (True, False): 3, (True, True): 1}[wheel, mirrors]
    options = ClimateRequestOptions(
        set_temp=temperature, duration=duration, climate=True, defrost=windshield, heating=heating,
        steering_wheel=1 if wheel else None,
        side_rear_mirror_heating=1 if mirrors else None,
    )
    return manager.start_climate(vehicle, options)


def stop(prepared):
    manager, vehicle, _ = prepared
    return manager.stop_climate(vehicle)


def result(prepared, action):
    manager, vehicle, _ = prepared
    return manager.check_action_status(vehicle, action, synchronous=True, timeout=60).value



def vehicle_in_use(prepared):
    manager, vehicle_id, _ = prepared
    manager.update_vehicle_with_cached_state(vehicle_id)
    return _is_in_use(manager.get_vehicle(vehicle_id))


def _is_in_use(vehicle):
    updated = _vehicle_time(vehicle)
    if updated is None:
        return False
    age = (datetime.now(UTC) - updated).total_seconds()
    if not 0 <= age <= 120:
        return False
    return vehicle.engine_is_running is True or any(
        locked is False for locked in (
            vehicle.is_locked,
            vehicle.front_left_door_is_locked,
            vehicle.front_right_door_is_locked,
            vehicle.back_left_door_is_locked,
            vehicle.back_right_door_is_locked,
        )
    )


def _vehicle_time(vehicle):
    # Some EU responses let the library substitute the local clock or a GPS timestamp.
    vehicle_time = get_child_value(vehicle.data, "Date") or get_child_value(vehicle.data, "vehicleStatus.time")
    updated = vehicle.last_updated_at
    return updated if vehicle_time and updated is not None and updated.utcoffset() is not None else None


def climate_environment(prepared):
    manager, vehicle_id, _ = prepared
    vehicle = manager.get_vehicle(vehicle_id)
    updated = _vehicle_time(vehicle)
    position = vehicle.location_last_updated_at
    latitude, longitude = vehicle.location_latitude, vehicle.location_longitude
    cell = location = None
    if position is not None and position.utcoffset() is not None and latitude is not None and longitude is not None:
        if (0 <= (datetime.now(UTC) - position).total_seconds() <= 86400
                and -90 <= latitude <= 90 and -180 <= longitude <= 180):
            cell = f"{latitude:.1f},{longitude:.1f}"
            location = f"{latitude:.6f},{longitude:.6f}"
    return _result(
        time=int(updated.timestamp() * 1000) if updated else 0,
        outside=_temperature_celsius(vehicle.outside_temperature, vehicle._outside_temperature_unit),
        target=_temperature_celsius(vehicle.air_temperature, vehicle._air_temperature_unit),
        locationTime=int(position.timestamp() * 1000) if location else 0,
        weatherCell=cell,
        weatherLocation=location,
    )


def _temperature_celsius(value, unit):
    if value is None:
        return None
    if unit == TEMPERATURE_C:
        return value
    if unit == TEMPERATURE_F:
        return (value - 32) * 5 / 9
    return None


def _vehicle_sample(vehicle):
    updated = _vehicle_time(vehicle)
    active = vehicle.air_control_is_on
    charging = vehicle.ev_battery_is_charging
    plugged = vehicle.ev_battery_is_plugged_in
    ignition = vehicle.engine_is_running
    return _result(
        time=int(updated.timestamp() * 1000) if updated else 0,
        climate=bool(active) if active in (0, 1) else None,
        inUse=_is_in_use(vehicle),
        percentage=vehicle.ev_battery_percentage,
        odometer=vehicle.odometer,
        ignition=bool(ignition) if ignition in (0, 1) else None,
        charging=bool(charging) if charging in (0, 1) else None,
        plugged=bool(plugged) if plugged in (0, 1, 2) else None,
    )


def cached_climate(prepared):
    manager, vehicle_id, _ = prepared
    vehicle = manager.get_vehicle(vehicle_id)
    if vehicle.air_control_is_on and (vehicle.engine_is_running is True or vehicle.is_locked is False):
        return _result()
    return _vehicle_sample(vehicle)


def force_climate_snapshot(prepared):
    manager, vehicle_id, _ = prepared
    manager.force_refresh_vehicle_state(vehicle_id)
    return _vehicle_sample(manager.get_vehicle(vehicle_id))


def observed_heating(prepared):
    manager, vehicle_id, _ = prepared
    vehicle = manager.get_vehicle(vehicle_id)
    updated = _vehicle_time(vehicle)
    return _result(
        time=int(updated.timestamp() * 1000) if updated else 0,
        windshield=vehicle.defrost_is_on,
        wheel=vehicle.steering_wheel_heater_is_on,
        rear=vehicle.back_window_heater_is_on,
        mirrors=vehicle.side_mirror_heater_is_on,
    )


def battery(prepared, force=False):
    manager, vehicle_id, _ = prepared
    if force:
        manager.force_refresh_vehicle_state(vehicle_id)
    else:
        manager.update_vehicle_with_cached_state(vehicle_id)
    return _vehicle_sample(manager.get_vehicle(vehicle_id))
