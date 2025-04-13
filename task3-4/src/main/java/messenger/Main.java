package messenger;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;

public class Main
{
    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder.forPort(9090)
                .addService(new MessengerImpl())
                .build();

        server.start();
        System.out.println("Server started at 9090");
        server.awaitTermination();
    }
}
