#!/bin/sh

categories="setup bestpractice coverage recommendation"

# Run semgrep rules for each category.
# if you want to send the results to ukulele, add ["-o", "http(s)://<IP>:<PORT>/<CATEGORY>","--json"] 
for category in $categories; do
    semgrep --config ./src/shippingservice/semgrep/$category-go.yaml ./src/shippingservice/. -o http://$1:$2/$category --json
done
