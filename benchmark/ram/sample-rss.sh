#!/bin/bash
# Samples the resident set size of a process once per second.
# Usage: ./sample-rss.sh <pid> > server-rss.log &
# Peak:  sort -t= -k2 -n server-rss.log | tail -1
pid=$1
while kill -0 "$pid" 2>/dev/null; do
    echo "$(date +%s) rss_kb=$(awk '/VmRSS/ {print $2}' /proc/$pid/status)"
    sleep 1
done
