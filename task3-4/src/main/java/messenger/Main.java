package messenger;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main
{
    private static final Map<String, StreamObserver<Messenger.MessageResponse>> clients = new ConcurrentHashMap<>();
    private static final Set<String> connectedUsers = ConcurrentHashMap.newKeySet();
    private static final int SECRET_MESSAGE_TTL = 10;

    private static final Jedis redis = new Jedis("localhost", 6379);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static void main(String[] args) throws IOException, InterruptedException
    {
        Server server = ServerBuilder.forPort(9090)
                .addService(new MessengerServiceImpl())
                .build();

        server.start();
        System.out.println("[SERVER] Started on port 9090");
        server.awaitTermination();
    }

    public static class MessengerServiceImpl extends MessengerServiceGrpc.MessengerServiceImplBase
    {

        @Override
        public void sendMessage(Messenger.MessageRequest request, StreamObserver<Messenger.SendResponse> responseObserver)
        {
            String to = request.getTo();
            String from = request.getFrom();
            boolean isSecret = request.getSecret();

            Messenger.MessageResponse msg = Messenger.MessageResponse.newBuilder()
                    .setFrom(from)
                    .setContent(request.getContent())
                    .setSystem(false)
                    .setSecret(isSecret)
                    .build();

            StreamObserver<Messenger.MessageResponse> receiver = clients.get(to);
            StreamObserver<Messenger.MessageResponse> sender = clients.get(from);

            if (connectedUsers.contains(to) && receiver != null)
            {
                try
                {
                    receiver.onNext(msg);
                    if (isSecret) scheduleDeletion(from, to, request.getContent(), receiver);
                }
                catch (Exception e)
                {
                    System.err.println("[SERVER] Failed to deliver message to " + to);
                }
            }
            else
            {
                storeMessageInRedis(from, to, request.getContent(), isSecret);
            }

            if (isSecret && sender != null)
            {
                scheduleDeletion(from, to, request.getContent(), sender);
            }

            Messenger.SendResponse response = Messenger.SendResponse.newBuilder()
                    .setStatus("Delivered")
                    .build();

            System.out.println("[DEBUG SERVER] Sended message to " + to + ", (secret)" + isSecret);

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        private void scheduleDeletion(String from, String to, String content, StreamObserver<Messenger.MessageResponse> observer)
        {
            scheduler.schedule(() ->
            {
                Messenger.MessageResponse deleteSignal = Messenger.MessageResponse.newBuilder()
                        .setFrom(from)
                        .setTo(to)
                        .setContent(content)
                        .setSystem(false)
                        .setSecret(true)
                        .setDelete(true)
                        .build();
                observer.onNext(deleteSignal);
                System.out.println("[DEBUG SERVER] Sent delete signal to " + to + " for: " + content);
            }, SECRET_MESSAGE_TTL, TimeUnit.SECONDS);
        }

        private void storeMessageInRedis(String from, String to, String content, boolean isSecret)
        {
            String key = "msg:" + to + ":" + UUID.randomUUID();
            redis.hset(key, Map.of(
                    "from", from,
                    "content", content,
                    "secret", Boolean.toString(isSecret)
            ));
            if (isSecret) redis.expire(key, SECRET_MESSAGE_TTL);
        }

        @Override
        public void receiveMessages(Messenger.ReceiveRequest request, StreamObserver<Messenger.MessageResponse> responseObserver)
        {
            String username = request.getUsername();
            clients.put(username, responseObserver);

            Set<String> keys = redis.keys("msg:" + username + ":*");
            for (String key : keys)
            {
                Map<String, String> data = redis.hgetAll(key);
                boolean isSecret = Boolean.parseBoolean(data.getOrDefault("secret", "false"));

                Messenger.MessageResponse msg = Messenger.MessageResponse.newBuilder()
                        .setFrom(data.get("from"))
                        .setContent(data.get("content"))
                        .setSystem(false)
                        .setSecret(isSecret)
                        .build();

                responseObserver.onNext(msg);

                if (isSecret) scheduleDeletion(data.get("from"), username, data.get("content"), responseObserver);

                redis.del(key);
            }
        }

        @Override
        public void disconnect(Messenger.DisconnectRequest request, StreamObserver<Messenger.Empty> responseObserver)
        {
            String username = request.getUsername();
            connectedUsers.remove(username);
            clients.remove(username);
            responseObserver.onNext(Messenger.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void connect(Messenger.ConnectRequest request, StreamObserver<Messenger.ConnectResponse> responseObserver)
        {
            String username = request.getUsername();
            connectedUsers.add(username);

            Messenger.ConnectResponse response = Messenger.ConnectResponse.newBuilder()
                    .addAllUsers(connectedUsers)
                    .build();

            for (Map.Entry<String, StreamObserver<Messenger.MessageResponse>> entry : clients.entrySet())
            {
                if (!entry.getKey().equals(username))
                {
                    Messenger.MessageResponse joinMsg = Messenger.MessageResponse.newBuilder()
                            .setFrom(username)
                            .setContent("joined")
                            .setSystem(true)
                            .build();
                    entry.getValue().onNext(joinMsg);
                }
            }

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
