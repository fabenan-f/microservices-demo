# Alpine image with installed python.
FROM python:3.9.1-alpine3.13

# Create work directory.
WORKDIR /code

# Copy everything from the current directory to the present working directory inside.
COPY . /code

# Install semgrep.
RUN python3 -m pip install semgrep

# Give permission to execute script.
RUN chmod +x ./semgrep_script.sh

# Run semgrep sh script.
ENTRYPOINT ["sh", "./semgrep_script.sh"]