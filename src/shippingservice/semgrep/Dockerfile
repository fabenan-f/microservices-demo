# Alpine image with installed python.
FROM python:3.9.1-alpine3.13

# Create work directory.
WORKDIR /semgrep

# Copy everything from the current directory to the present working directory inside.
COPY . /semgrep

# Install semgrep.
RUN python3 -m pip install semgrep

# Run semgrep sh script.
ENTRYPOINT ["sh", "./src/shippingservice/semgrep/semgrep_go_script.sh"]