#!/usr/bin/env bash
set -e
set +x

# Generate and print the config file
echo "Starting Stunnel with configuration:"
"${STUNNEL_HOME}"/entity_operator_stunnel_config_generator.sh | tee /tmp/stunnel.conf
echo ""

set -x

# starting Stunnel with final configuration
exec /usr/bin/tini -w -e 143 -- /usr/bin/stunnel /tmp/stunnel.conf
