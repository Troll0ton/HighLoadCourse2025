package messenger;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Main
{
    private static final Map<String, StreamObserver<Messenger.MessageResponse>> clients = new ConcurrentHashMap<>();
    private static final Set<String> connectedUsers = ConcurrentHashMap.newKeySet();
    private static final int SECRET_MESSAGE_TTL = 10;

    private static final Jedis redis = new Jedis("localhost", 6379);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Map<String, String> channels = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> channelMessageCounts = new ConcurrentHashMap<>();

    private static final Map<String, Set<StreamObserver<Messenger.MessageResponse>>> channelSubscribers = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException, InterruptedException
    {
        int workerCount = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Server server = startServer(workerCount);
        server.awaitTermination();
    }

    public static Server startServer(int workerCount) throws IOException, InterruptedException
    {
        Server server = ServerBuilder.forPort(9090)
                .executor(Executors.newFixedThreadPool(workerCount))
                .addService(new MessengerServiceImpl())
                .build();

        server.start();
        System.out.println("[SERVER] Started on port 9090");

        return server;
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
        public void getChannelStats(Messenger.ChannelStatsRequest request, StreamObserver<Messenger.ChannelStatsResponse> responseObserver)
        {
            String channelId = request.getChannelId();
            int count = channelMessageCounts.getOrDefault(channelId, new AtomicInteger(0)).get();

            Messenger.ChannelStatsResponse response = Messenger.ChannelStatsResponse.newBuilder()
                    .setTotalMessages(count)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void sendChannelMessage(Messenger.ChannelMessageRequest request,
                                       StreamObserver<Messenger.SendResponse> responseObserver)
        {
            String channelId = request.getChannelId();
            String from = request.getFrom();
            String content = request.getContent();

            channelMessageCounts.computeIfAbsent(channelId, k -> new AtomicInteger()).incrementAndGet();

            Messenger.MessageResponse msg = Messenger.MessageResponse.newBuilder()
                    .setFrom(from)
                    .setContent(content)
                    .setSystem(false)
                    .build();

            Set<StreamObserver<Messenger.MessageResponse>> subscribers =
                    channelSubscribers.getOrDefault(channelId, Collections.emptySet());

            Iterator<StreamObserver<Messenger.MessageResponse>> it = subscribers.iterator();
            while (it.hasNext())
            {
                StreamObserver<Messenger.MessageResponse> obs = it.next();
                try
                {
                    obs.onNext(msg);
                }
                catch (RuntimeException ex)
                {
                    it.remove();
                }
            }

            responseObserver.onNext(
                    Messenger.SendResponse.newBuilder().setStatus("Delivered").build()
            );
            responseObserver.onCompleted();
        }

        @Override
        public void receiveChannelMessages(Messenger.ChannelReceiveRequest request,
                                           StreamObserver<Messenger.MessageResponse> responseObserver)
        {
            String channelId = request.getChannelId();
            ServerCallStreamObserver<Messenger.MessageResponse> serverObs =
                    (ServerCallStreamObserver<Messenger.MessageResponse>) responseObserver;

            serverObs.setOnCancelHandler(() ->
                    channelSubscribers.getOrDefault(channelId, Collections.emptySet())
                            .remove(responseObserver)
            );

            channelSubscribers
                    .computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet())
                    .add(responseObserver);
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

        @Override
        public void createChannel(Messenger.CreateChannelRequest request, StreamObserver<Messenger.Empty> responseObserver)
        {
            String id = request.getName();
            String name = request.getName();
            String creator = request.getCreator();
            List<String> tags = request.getTagsList();

            channels.put(id, creator);
            redis.hset("channel:" + id, "creator", creator);
            redis.hset("channel:" + id, "tags", String.join(",", tags));

            Messenger.MessageResponse broadcast = Messenger.MessageResponse.newBuilder()
                    .setFrom(creator)
                    .setContent(name)
                    .setSystem(true)
                    .setTo("CHANNEL:" + id)
                    .addAllTags(tags)
                    .build();

            for (Map.Entry<String, StreamObserver<Messenger.MessageResponse>> entry : clients.entrySet())
            {
                entry.getValue().onNext(broadcast);
            }

            responseObserver.onNext(Messenger.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}
