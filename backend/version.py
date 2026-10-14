"""
version.py: single source of truth for the application identity and version.

Semantic versioning (MAJOR.MINOR.PATCH):
  MAJOR: incompatible architectural or data changes
  MINOR: backward-compatible features
  PATCH: backward-compatible bug fixes

The database schema version is tracked separately (see database.py), because
a code release does not always change the schema.
"""
APP_NAME = "Abhify.AI"
APP_ID = "AbhifyAI"            # used for the per-user data directory name
__version__ = "2.0.0-dev.2"

# Oldest application version whose user data this release can upgrade in place.
MIN_UPGRADABLE_VERSION = "1.0.0"
