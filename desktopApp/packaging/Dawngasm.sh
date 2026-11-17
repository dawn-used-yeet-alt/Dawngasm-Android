#!/bin/sh
export LD_LIBRARY_PATH="/usr/lib/dawngasm:${LD_LIBRARY_PATH}"
export PATH="/usr/lib/dawngasm:${PATH}"
exec /usr/lib/dawngasm/Dawngasm "$@"
