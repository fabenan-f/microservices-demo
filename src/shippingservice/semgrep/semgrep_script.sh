#!/bin/sh

# Two notes: 
# 1. Declare path with the path to your service relative to repo's directory
# 2. If you want to send the results to Ukulele without GitHub Actions on a local machine, 
# add localhost and port 8080 as arguments when running the script 

categories="setup bestpractice coverage recommendation"
language="go"
pathToSemgrepDir="./src/shippingservice/semgrep"
pathToService="./src/shippingservice/"
for category in $categories; do
    semgrep --config $pathToSemgrepDir/$category-$language.yaml $pathToService -o http://$1:$2/$category --json
done

