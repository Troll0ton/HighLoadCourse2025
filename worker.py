import pika
import json
import smtplib
from email.mime.text import MIMEText

RABBITMQ_HOST = 'localhost'
QUEUE_REGISTRATION = 'email_registration'
QUEUE_PASSWORD_RESET = 'email_password_reset'

SMTP_SERVER = 'smtp.gmail.com'  # SMTP-сервер, например, Gmail
SMTP_PORT = 587  # Порт (587 для STARTTLS или 465 для SSL/TLS)
SMTP_USER = 'anton.451538@gmail.com'  # Логин (ваш email)
SMTP_PASSWORD = 'bmxe oesw wsvq ypuy'  # Пароль (или приложение-пароль)

def send_email(to_email, subject, body):
    data = json.loads(body)
    email = data['email']
    subject = data['subject']
    body_text = data['body']

    # Отправка email
    send_email(email, subject, body_text)

def callback(ch, method, properties, body):
    data = json.loads(body)
    email = data['email']
    token = data['token']

    if method.routing_key == QUEUE_REGISTRATION:
        subject = 'Complete your registration'
        link = f'http://localhost:5000/verify-token/{token}'
        body = f'Please complete your registration by clicking the following link: {link}'
        send_email(email, subject, body)

    elif method.routing_key == QUEUE_PASSWORD_RESET:
        subject = 'Reset your password'
        link = f'http://localhost:5000/reset-password/{token}'
        body = f'Reset your password by clicking the following link: {link}'
        send_email(email, subject, body)

connection = pika.BlockingConnection(pika.ConnectionParameters(host=RABBITMQ_HOST))
channel = connection.channel()

channel.queue_declare(queue=QUEUE_REGISTRATION, durable=True)
channel.queue_declare(queue=QUEUE_PASSWORD_RESET, durable=True)

channel.basic_consume(queue=QUEUE_REGISTRATION, on_message_callback=callback, auto_ack=True)
channel.basic_consume(queue=QUEUE_PASSWORD_RESET, on_message_callback=callback, auto_ack=True)

print('Waiting for messages...')
channel.start_consuming()