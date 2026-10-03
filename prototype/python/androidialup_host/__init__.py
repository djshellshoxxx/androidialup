"""PC-side Android Open Accessory bridge support for AndroidDialup."""

from .aoa import AccessoryIdentity, AoaProtocolError, enter_accessory_mode

__all__ = ["AccessoryIdentity", "AoaProtocolError", "enter_accessory_mode"]
