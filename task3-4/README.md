# Simple Messenger

Добавлена поддержка redis для сообщений, удаление секретных сообщений через промежуток времени.

### Скомпилировать классы protoc
```
mvn clean compile
```

### Собрать и запустить сервер
```
mvn compile exec:java "-Dexec.mainClass=messenger.Main"
```

### Запустить клиент в консоли
```
mvn compile exec:java "-Dexec.mainClass=messenger.Client"
```