import json
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

import pika

QUEUE_REGISTRATION = 'reg_queue'
QUEUE_PASSWORD_RESET = 'reset_queue'


def send_email(to_email, application_password, subject, body):
    server = smtplib.SMTP('smtp.gmail.com', 587)
    server.starttls()
    server.login(to_email, application_password)

    msg = MIMEMultipart()
    msg['From'] = to_email
    msg['To'] = to_email
    msg['Subject'] = subject
    msg.attach(MIMEText(body, 'plain'))
    server.sendmail(to_email, to_email, msg.as_string())

    print(f"message was sent to {to_email}")
    server.quit()


def parse_data(body):
    data = json.loads(body)
    email = data['email']
    token = data['token']
    application_password = data['application_password']

    return email, token, application_password


def send_reg(ch, method, properties, body):
    email, token, application_password = parse_data(body)

    subject = 'Complete your registration'
    body = f'Here is your token. You can use this token for set-password on localhost: {token}'
    send_email(email, application_password, subject, body)


def send_reset(ch, method, properties, body):
    email, token, application_password = parse_data(body)

    subject = 'Reset your password'
    body = f'Here is your token. You can use this token for reset-password on localhost: {token}'
    send_email(email, application_password, subject, body)


init = pika.BlockingConnection(pika.ConnectionParameters(host='localhost'))
worker = init.channel()

worker.queue_declare(queue=QUEUE_REGISTRATION, durable=False)
worker.queue_declare(queue=QUEUE_PASSWORD_RESET, durable=False)
worker.basic_consume(queue=QUEUE_REGISTRATION, on_message_callback=send_reg, auto_ack=True)
worker.basic_consume(queue=QUEUE_PASSWORD_RESET, on_message_callback=send_reset, auto_ack=True)

worker.start_consuming()
