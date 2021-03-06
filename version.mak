# Version Information are taken from pom.xml

# Milestone is manually specified,
# example for ordering:
# - master
# - alpha
# - master
# - beta
# - master
# - beta2
# - master
# - rc
# - master
# - rc2
# - master
# - <none>
#
MILESTONE=master

# RPM release is manually specified,
# For pre-release:
# RPM_RELEASE=0.N.$(MILESTONE).$(shell date -u +%Y%m%d%H%M%S)
# While N is incremented when milestone is changed.
#
# For release:
# RPM_RELEASE=N
# while N is incremented each re-release
#
RPM_RELEASE=0.2.$(MILESTONE).$(shell date -u +%Y%m%d%H%M%S)

#
# Downstream only release prefix
# Downstream (mead) does not use RPM_RELEASE but internal
# mead versioning.
# Increment the variable bellow after every milestone is
# released.
# Or leave empty to have only mead numbering.
#
DOWNSTREAM_RPM_RELEASE_PREFIX=0.