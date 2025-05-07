from flask import Flask, jsonify, request
from datetime import datetime
import argparse

service = Flask(__name__)

RECORD_NUMBER = 10000

@service.route('/date_service', methods=['GET'])
def date_service():
    now = datetime.now()
    
    records = []
    for _ in range(RECORD_NUMBER):
        records.append({"year": now.year, "month": now.month, "day": now.day})
    
    return jsonify(records)

@service.route('/name_service', methods=['POST'])
def name_service():
    name = request.form.get('name') 
    
    records = []
    for _ in range(RECORD_NUMBER):
        records.append({"name": name})
        
    return jsonify(records)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8000)
    args = parser.parse_args()
    
    service.run(host='0.0.0.0', port=args.port)