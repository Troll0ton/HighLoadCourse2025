from flask import Flask, request, jsonify, redirect, url_for
from redis import Redis
import uuid
import time
import pika
import json

app = Flask(__name__)

# Redis: Хранилище токенов
redis_client = Redis(host='localhost', port=6379, decode_responses=True)

# Конфигурация RabbitMQ
RABBITMQ_HOST = 'localhost'
QUEUE_REGISTRATION = 'email_registration'
QUEUE_PASSWORD_RESET = 'email_password_reset'

def send_to_queue(queue, message):
    connection = pika.BlockingConnection(pika.ConnectionParameters(host=RABBITMQ_HOST))
    channel = connection.channel()
    channel.queue_declare(queue=queue)
    channel.basic_publish(exchange='', routing_key=queue, body=json.dumps(message))
    connection.close()

@app.route('/register', methods=['POST'])
def register():
    """Регистрация пользователя: отправка письма с токеном"""
    email = request.json.get('email')
    if not email:
        return jsonify({'error': 'Email is required'}), 400

    # Генерация токена
    token = str(uuid.uuid4())
    redis_client.setex(f'registration:{token}', 3600, email)  # Токен валиден 1 час

    # Отправка сообщения в очередь
    send_to_queue(QUEUE_REGISTRATION, {'email': email, 'token': token})
    return jsonify({'message': 'Registration email sent.'}), 200

@app.route('/verify-token/<string:token>', methods=['GET'])
def verify_token(token):
    """Валидация токена регистрации"""
    email = redis_client.get(f'registration:{token}')
    if not email:
        return jsonify({'error': 'Invalid or expired token.'}), 400

    # Удаляем токен, чтобы его нельзя было повторно использовать
    redis_client.delete(f'registration:{token}')
    return jsonify({'message': 'Token is valid. You can set your password', 'email': email}), 200

@app.route('/set-password', methods=['POST'])
def set_password():
    """Установка пароля для пользователя"""
    email = request.json.get('email')
    password = request.json.get('password')

    if not email or not password:
        return jsonify({'error': 'Email and password are required'}), 400

    # Сохраняем пользователя (в реальной системе — БД)
    redis_client.set(f'user:{email}', password)
    return jsonify({'message': 'Password set successfully!'}), 200

@app.route('/forgot-password', methods=['POST'])
def forgot_password():
    """Обработка формы восстановления пароля: отправка нового токена"""
    email = request.json.get('email')
    if not email:
        return jsonify({'error': 'Email is required'}), 400

    # Проверяем, существует ли пользователь
    if not redis_client.exists(f'user:{email}'):
        return jsonify({'error': 'User not found'}), 404

    # Генерация токена восстановления пароля
    token = str(uuid.uuid4())
    redis_client.setex(f'password_reset:{token}', 3600, email)

    # Отправка сообщения в очередь
    send_to_queue(QUEUE_PASSWORD_RESET, {'email': email, 'token': token})
    return jsonify({'message': 'Password reset email sent.'}), 200

@app.route('/reset-password/<string:token>', methods=['POST'])
def reset_password(token):
    """Сброс пароля по токену"""
    email = redis_client.get(f'password_reset:{token}')
    if not email:
        return jsonify({'error': 'Invalid or expired token.'}), 400

    password = request.json.get('password')
    if not password:
        return jsonify({'error': 'Password is required'}), 400

    # Устанавливаем новый пароль
    redis_client.set(f'user:{email}', password)
    redis_client.delete(f'password_reset:{token}')
    return jsonify({'message': 'Password has been reset.'}), 200

if __name__ == '__main__':
    app.run(debug=True)