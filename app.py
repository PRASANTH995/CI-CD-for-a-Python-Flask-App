from flask import Flask, jsonify
import os
import socket

app = Flask(__name__)

@app.route('/')
def hello():
    return jsonify({
        "message": "Welcome to Flask CI/CD Demo",
        "status": "success",
        "version": "1.0.0",
        "hostname": socket.gethostname()
    })

@app.route('/status')
def status():
    return jsonify({
        "status": "healthy",
        "service": "flask-app",
        "container_id": os.uname().nodename
    })

@app.route('/health')
def health():
    return jsonify({"status": "OK"}), 200

@app.route('/info')
def info():
    return jsonify({
        "python_version": os.environ.get('PYTHON_VERSION', '3.9'),
        "environment": os.environ.get('ENVIRONMENT', 'development'),
        "host": socket.gethostname()
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
