package messenger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.Scanner;

public class Client
{
    private static final int CLIENT_PORT = 9090;

    public static void main(String[] args) throws Exception
    {
        Scanner scanner = new Scanner(System.in);

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", CLIENT_PORT)
                .usePlaintext()
                .build();

        MessengerServiceGrpc.MessengerServiceStub asyncStub = MessengerServiceGrpc.newStub(channel);
        MessengerServiceGrpc.MessengerServiceBlockingStub blockingStub = MessengerServiceGrpc.newBlockingStub(channel);

        System.out.print("Enter your name: ");
        String nickname = scanner.nextLine();

        asyncStub.receiveMessages(Messenger.ReceiveRequest.newBuilder().setUsername(nickname).build(),
                new ClientStreamObserver());

        while (true)
        {
            sendMessage(scanner, blockingStub, nickname);
        }
    }

    private static void sendMessage(Scanner scanner, MessengerServiceGrpc.MessengerServiceBlockingStub blockingStub, String nickname)
    {
        System.out.print("Write to: ");
        String to = scanner.nextLine();
        System.out.print("Message: ");
        String content = scanner.nextLine();

        Messenger.MessageRequest request = Messenger.MessageRequest.newBuilder()
                .setFrom(nickname)
                .setTo(to)
                .setContent(content)
                .build();

        blockingStub.sendMessage(request);
    }

    private static class ClientStreamObserver implements StreamObserver<Messenger.MessageResponse>
    {
        @Override
        public void onNext(Messenger.MessageResponse msg)
        {
            System.out.println("\n(from: " + msg.getFrom() + ") " + msg.getContent());
            System.out.print("Write to: ");
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
}