package messenger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MessengerStressTests
{
    private static ExecutorService serverExec;
    private static Future<?> serverFuture;

    private static final int CLIENTS = 150;
    private static final int ADMINS = 10;
    private static final int MESSAGES = 100;

    @AfterEach
    void tearDownServer()
    {
        if (serverFuture != null) serverFuture.cancel(true);
        if (serverExec != null) serverExec.shutdownNow();
    }

    private void startServer(int workerThreads) throws InterruptedException
    {
        serverExec = Executors.newSingleThreadExecutor();
        serverFuture = serverExec.submit(() ->
        {
            try
            {
                Main.startServer(workerThreads);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(1_000);
    }

    @Test
    @Order(1)
    void stressWith1Worker() throws Exception
    {
        runAndMeasure(1);
    }

    @Test
    @Order(1)
    void stressWith2Worker() throws Exception
    {
        runAndMeasure(2);
    }

    @Test
    @Order(2)
    void stressWith4Workers() throws Exception
    {
        runAndMeasure(4);
    }

    @Test
    @Order(3)
    void stressWith8Workers() throws Exception
    {
        runAndMeasure(8);
    }

    private void runAndMeasure(int workers) throws Exception
    {
        startServer(workers);

        long t0 = System.nanoTime();
        assertTrue(runStressScenario(), "Stress scenario failed");
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        System.out.printf("%d worker(s): %.2f ms%n", workers, ms);
    }

    private boolean runStressScenario() throws InterruptedException
    {
        System.out.println("=== Running stress scenario ===");

        int totalMessages = (CLIENTS - ADMINS) * ADMINS * MESSAGES;
        CountDownLatch receiveLatch = new CountDownLatch(totalMessages);
        CountDownLatch adminDoneLatch = new CountDownLatch(ADMINS);
        CountDownLatch readyLatch = new CountDownLatch(CLIENTS);
        CountDownLatch finishLatch = new CountDownLatch(CLIENTS);

        ExecutorService clientExec = Executors.newFixedThreadPool(CLIENTS);
        for (int i = 0; i < CLIENTS; i++)
        {
            clientExec.submit(new ClientTask(
                    i, receiveLatch, adminDoneLatch, readyLatch, finishLatch
            ));
        }

        readyLatch.await();
        adminDoneLatch.await();
        receiveLatch.await();
        finishLatch.await();

        clientExec.shutdownNow();
        System.out.println("=== Stress scenario completed ===");
        return true;
    }

    private static class ClientTask implements Runnable
    {
        private final int index;
        private final String username;
        private final boolean isAdmin;
        private final CountDownLatch receiveLatch;
        private final CountDownLatch adminDoneLatch;
        private final CountDownLatch readyLatch;
        private final CountDownLatch finishLatch;

        ClientTask(int index,
                   CountDownLatch receiveLatch,
                   CountDownLatch adminDoneLatch,
                   CountDownLatch readyLatch,
                   CountDownLatch finishLatch)
        {
            this.index = index;
            this.username = "user_" + index;
            this.isAdmin = index < ADMINS;
            this.receiveLatch = receiveLatch;
            this.adminDoneLatch = adminDoneLatch;
            this.readyLatch = readyLatch;
            this.finishLatch = finishLatch;
        }

        @Override
        public void run()
        {
            ManagedChannel channel = createChannel();
            var asyncStub = MessengerServiceGrpc.newStub(channel);
            var blockingStub = MessengerServiceGrpc.newBlockingStub(channel);

            try
            {
                connectToServer(blockingStub);
                if (isAdmin) createOwnChannel(blockingStub);

                subscribeToAllChannels(asyncStub);
                signalReadyAndAwaitAll();

                if (isAdmin) sendAdminMessages(asyncStub);

                waitForAllMessages();

            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                finishLatch.countDown();
                channel.shutdownNow();
            }
        }

        private ManagedChannel createChannel()
        {
            return ManagedChannelBuilder
                    .forAddress("localhost", 9090)
                    .usePlaintext()
                    .build();
        }

        private void connectToServer(MessengerServiceGrpc.MessengerServiceBlockingStub stub)
        {
            stub.connect(Messenger.ConnectRequest.newBuilder()
                    .setUsername(username)
                    .build());
        }

        private void createOwnChannel(MessengerServiceGrpc.MessengerServiceBlockingStub stub)
        {
            String myChannel = "channel_" + index;
            stub.createChannel(Messenger.CreateChannelRequest.newBuilder()
                    .setCreator(username)
                    .setName(myChannel)
                    .build());
        }

        private void subscribeToAllChannels(MessengerServiceGrpc.MessengerServiceStub stub)
        {
            for (int c = 0; c < ADMINS; c++)
            {
                String chId = "CHANNEL:channel_" + c;
                stub.receiveChannelMessages(
                        Messenger.ChannelReceiveRequest.newBuilder()
                                .setUsername(username)
                                .setChannelId(chId)
                                .build(),
                        new StreamObserver<>()
                        {
                            @Override
                            public void onNext(Messenger.MessageResponse msg)
                            {
                                receiveLatch.countDown();
                            }

                            @Override
                            public void onError(Throwable t)
                            {
                            }

                            @Override
                            public void onCompleted()
                            {
                            }
                        }
                );
            }
        }

        private void signalReadyAndAwaitAll() throws InterruptedException
        {
            readyLatch.countDown();
            readyLatch.await();
        }

        private void sendAdminMessages(MessengerServiceGrpc.MessengerServiceStub stub)
        {
            String myChannel = "CHANNEL:channel_" + index;
            for (int i = 0; i < MESSAGES; i++)
            {
                stub.sendChannelMessage(
                        Messenger.ChannelMessageRequest.newBuilder()
                                .setFrom(username)
                                .setChannelId(myChannel)
                                .setContent("[" + username + "] msg " + i)
                                .build(),
                        new StreamObserver<>()
                        {
                            @Override
                            public void onNext(Messenger.SendResponse r)
                            {
                            }

                            @Override
                            public void onError(Throwable t)
                            {
                            }

                            @Override
                            public void onCompleted()
                            {
                            }
                        }
                );
            }
            adminDoneLatch.countDown();
        }

        private void waitForAllMessages() throws InterruptedException
        {
            if (!isAdmin)
            {
                adminDoneLatch.await();
            }

            receiveLatch.await();
        }
    }
}
