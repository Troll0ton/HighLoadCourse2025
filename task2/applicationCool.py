import json
import uuid

import pika
from flask import Flask, request, jsonify
from redis import Redis

app = Flask(__name__)

redis_client = Redis(host='localhost', port=6379, decode_responses=True)

@app.route('/authorization_and_get', methods=['POST'])
def authorization_and_get():
    email = request.json.get('email')
    password = request.json.get('password')

    if not redis_client.exists(f'user:{email}'):
        return jsonify({'message': f'This user doesn\'t exist!'}), 200

    input_password = redis_client.get(f'user:{email}')
    if input_password != password:
        return jsonify({'message': 'Invalid password'}), 200

    return jsonify({'message': f'Some very secret information for email {email}'}), 200


@app.route('/dump', methods=['POST'])
def dump():
    email = request.json.get('email')
    if not redis_client.exists(f'user:{email}'):
        return jsonify({'message': f'This user doesn\'t exist!'}), 200
    current_password = redis_client.get(f'user:{email}')

    return jsonify({'message': f'user {email}: (password) {current_password}'}), 200


# Регистрация пользователя (нужно указать адрес почты и пароль приложения)
@app.route('/reg', methods=['POST'])
def reg():
    email = request.json.get('email')
    if redis_client.exists(f'user:{email}'):
        return jsonify({'error': 'This user already exists!'}), 404

    application_password = request.json.get('application_password')
    token = create_token('registration', email)

    send_to_queue('reg_queue', {'email': email, 'application_password': application_password, 'token': token})
    return jsonify({'message': f'Send mail for registration confirmation: {email}.'}), 200


# Установка пароля для пользователя
@app.route('/set_p/<string:token>', methods=['POST'])
def set_p(token):
    if not redis_client.exists(f'registration:{token}'):
        return jsonify({'error': 'This token is invalid!'}), 404

    email = redis_client.get(f'registration:{token}')
    password = request.json.get('password')

    redis_client.set(f'user:{email}', password)
    redis_client.delete(f'registration:{token}')

    return jsonify({'message': f'The password {password} was set for email: {email}.'}), 200


# Запрос на восстановление пароля
@app.route('/forgot', methods=['POST'])
def forgot():
    email = request.json.get('email')
    if not redis_client.exists(f'user:{email}'):
        return jsonify({'error': 'There is no such user!'}), 404

    application_password = request.json.get('application_password')
    token = create_token('reset', email)

    send_to_queue('reset_queue',
                  {'email': email, 'application_password': application_password, 'token': token})
    return jsonify({'message': f'Send mail for reset confirmation: {email}.'}), 200


# Восстановление пароля
@app.route('/reset_p/<string:token>', methods=['POST'])
def reset_p(token):
    if not redis_client.exists(f'reset:{token}'):
        return jsonify({'error': 'This token is invalid!'}), 404

    email = redis_client.get(f'reset:{token}')
    password = request.json.get('password')

    redis_client.set(f'user:{email}', password)
    redis_client.delete(f'reset:{token}')

    return jsonify({'message': f'The password was changed for email: {email}.'}), 200


def send_to_queue(queue, message):
    connection = pika.BlockingConnection(pika.ConnectionParameters(host='localhost'))
    channel = connection.channel()
    channel.queue_declare(queue=queue)
    channel.basic_publish(exchange='', routing_key=queue, body=json.dumps(message))
    connection.close()


def create_token(purpose, email):
    token = str(uuid.uuid4())
    redis_client.setex(f'{purpose}:{token}', 7200, email)
    return token


if __name__ == '__main__':
    redis_client.flushdb()
    app.run()
