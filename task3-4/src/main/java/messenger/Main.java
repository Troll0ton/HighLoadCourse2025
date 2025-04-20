package messenger;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Main
{
    private static final int SERVER_PORT = 9090;

    public static void main(String[] args) throws IOException, InterruptedException
    {
        Server server = ServerBuilder.forPort(SERVER_PORT)
                .addService(new MessengerImpl())
                .build();

        server.start();
        server.awaitTermination();
    }

    public static class MessengerImpl extends MessengerServiceGrpc.MessengerServiceImplBase
    {
        private final Map<String, StreamObserver<Messenger.MessageResponse>> listeners = new ConcurrentHashMap<>();

        @Override
        public void sendMessage(Messenger.MessageRequest request, StreamObserver<Messenger.SendResponse> responseObserver)
        {
            Messenger.MessageResponse msg = Messenger.MessageResponse.newBuilder()
                    .setFrom(request.getFrom())
                    .setContent(request.getContent())
                    .build();

            StreamObserver<Messenger.MessageResponse> receiver = listeners.get(request.getTo());
            if (receiver != null)
            {
                receiver.onNext(msg);
            }

            Messenger.SendResponse res = Messenger.SendResponse.newBuilder().setStatus("Delivered").build();
            responseObserver.onNext(res);
            responseObserver.onCompleted();
        }

        @Override
        public void receiveMessages(Messenger.ReceiveRequest request, StreamObserver<Messenger.MessageResponse> responseObserver)
        {
            listeners.put(request.getUsername(), responseObserver);
        }
    }
}
