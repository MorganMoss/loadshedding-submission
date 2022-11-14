package wethinkcode.service;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.json.JsonMapper;
import io.javalin.plugin.bundled.CorsPluginConfig;
import picocli.CommandLine;
import wethinkcode.router.Controllers;

import java.lang.annotation.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Logger;

import static wethinkcode.logger.Logger.formatted;
import static wethinkcode.service.Checks.*;
import static wethinkcode.service.Properties.populateFields;

public class Service<E>{
    /**
     * The class annotated as a service
     */
    public final E instance;
    /**
     * The javalin server used to host this service.
     */
    private Javalin server;
    /**
     * Port for the service
     */
    @CommandLine.Option(
            names = {"--port", "-p"},
            description = "The name of a directory where CSV datafiles may be found. This option overrides and data-directory setting in a configuration file.",
            type = Integer.class
    )
    public Integer port=0;

    public E getInstance(){
        return instance;
    }

    /**
     * Commands enables/disables
     */
    @CommandLine.Option(
            names = {"--commands", "-o"},
            description = "Enables or Disables Commands during runtime from sys.in",
            type = Boolean.class
    )
    Boolean commands = false;


    @CommandLine.Option(
            names = {"--domain", "-dom"},
            description = "The host name of the server"
    )
    String domain = "http://localhost";

    /**
     * Used for waiting
     */
    private final Object lock = new Object();
    private boolean started = false;
    private boolean stopped = false;

    private final Logger logger;

    private static final String ANNOTATION_COLOUR = "\u001B[38;5;221m";
    private static final String SERVER_COLOUR = "\u001B[38;5;215m";


    public Service(E instance){
        checkClassAnnotation(instance.getClass());
        this.instance = instance;
        logger = formatted("Annotation Handler: " + instance.getClass().getSimpleName(), SERVER_COLOUR, ANNOTATION_COLOUR);
    }

    /**
     * Run this method to create a new service from a class you have annotated with @AsService.
     * <br/><br/>
     * Use picocli's @CommandLine.Option for custom fields to have them instantiated by the Properties of this service
     * @return A Service object with an instance of your class.
     */
    public Service<E> execute(String ... args){
        Method[] methods = instance.getClass().getMethods();
        initProperties(args);
        logger.info("Properties Instantiated");
        initHttpServer(methods);
        logger.info("Javalin Server Created");
        handleMethods(methods, RunOnInitialisation.class);
        logger.info("Initialization Methods Run");
        activate();
        logger.info("Service Started");
        handleMethods(methods, RunOnPost.class);
        logger.info("Post Methods Run");
        return this;
    }
    
    public void close(){
        if (stopped){
            throw new AlreadyStoppedException("This service is designed to be stopped once");
        }
        stopped = true;
        server.stop();
    }

    public void run() {
        if (started){
            throw new AlreadyStartedException("This service is designed to be run once");
        }

        started = true;
        server.start(port);

        synchronized (lock){
            lock.notify();
        }

        if (commands) {
            logger.info("Commands are active");
            Thread th = new Thread(this::startCommands);
            th.setName("Command Handler " + instance.getClass().getSimpleName());
            th.start();

        }
    }

    /**
     * Takes a service and runs it in a separate thread.
     */
    @SuppressWarnings("SleepWhileHoldingLock")
    private void activate(){
        Thread t = new Thread(this::run);
        t.setName(this.instance.getClass().getSimpleName());
        t.start();

        try {
            synchronized (lock){
                lock.wait();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Allows accepting certain general commands from sys.in during runtime
     */
    private void startCommands(){
        Scanner s = new Scanner(System.in);
        String nextLine;
        while ((nextLine = s.nextLine())!=null) {
            String[] args = nextLine.split(" ");
            switch (args[0].toLowerCase()) {
                case "quit" -> {
                    close();
                    System.exit(0);
                    return;
                }

                case "help" -> System.out.println(
                        """
                                commands available:
                                    'help' - list of commands
                                    'quit' - close the service
                                """
                );
            }
        }
    }

    private void handleCustomJavalinConfigs(Method[] methods, JavalinConfig javalinConfig) {
        logger.info("Handling Custom Javalin Configs");
        Arrays
                .stream(methods)
                .filter(method -> method.isAnnotationPresent(CustomJavalinConfig.class))
                .forEach(method -> handleCustomJavalinConfig(method, javalinConfig));
    }

    private void handleCustomJavalinConfig(Method method, JavalinConfig javalinConfig) {
        logger.info("Invoking " + method.getName());
        checkHasJavalinConfigAsArg(method);
        try {
            method.invoke(instance, javalinConfig);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private JsonMapper handleCustomJSONMapper(Method[] methods){
        List<Method> mapper = Arrays
                .stream(methods)
                .filter(method -> method.isAnnotationPresent(CustomJSONMapper.class))
                .toList();

        if (mapper.size() > 1){
            throw new MultipleJSONMapperMethodsException(instance.getClass().getSimpleName() + " has more than one custom JSON Mapper");
        }

        if (mapper.isEmpty()){
            return createJsonMapper();
        }

        Method method = mapper.get(0);
        checkForNoArgs(method);
        checkHasReturnType(method, JsonMapper.class);
        try {
            return (JsonMapper) method.invoke(instance);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Use GSON for serialisation instead of Jackson by default
     * because GSON allows for serialisation of objects without noargs constructors.
     *
     * @return A JsonMapper for Javalin
     */
    private JsonMapper createJsonMapper() {
        return new GSONMapper(instance.getClass().getSimpleName());
    }

    private void handleMethods(Method[] methods, Class<? extends Annotation> annotation) {
        Arrays
                .stream(methods)
                .filter(method -> method.isAnnotationPresent(annotation))
                .forEach(method -> handleMethod(method , annotation));
    }

    private void handleMethod(Method method, Class<? extends Annotation> annotationClass){
        boolean port = false;
        logger.info("Attempting to invoke " + method.getName());

        if (annotationClass.equals(RunOnPost.class)) {
            port = method.getAnnotation(RunOnPost.class).withServiceAsArg();
        }
        if (annotationClass.equals(RunOnInitialisation.class)) {
            port = method.getAnnotation(RunOnInitialisation.class).withServiceAsArg();
        }


//        TypeToken.of(Service<E>)

        if (port){
//            checkHasArgs(method, );
            try {
                method.invoke(instance, this);
                logger.info("Invoked " + method.getName() + " successfully");
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
            return;
        }

        checkForNoArgs(method);
        try {
            method.invoke(instance);
            logger.info("Invoked " + method.getName() + " successfully");
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Handles the CLI and properties file to configure the service
     *
     * @param args CLI arguments
     */
    private void initProperties(String... args) {
        populateFields(this, instance, args);
    }

    /**
     * Gets the routes from the routes package in the given services package
     */
    private void addRoutes(){
        new Controllers(instance).getEndpoints().forEach(server::routes);
    }


    /**
     * Creates the javalin server.
     * By default, this just loads the JsonMapper from createJsonMapper
     */
    private void initHttpServer(Method[] methods) {
        server = Javalin.create(
            javalinConfig -> {
                handleCustomJavalinConfigs(methods, javalinConfig);
                if (instance.getClass().getAnnotation(AsService.class).AnyHost()){
                    javalinConfig.plugins.enableCors(cors -> {
                        cors.add(CorsPluginConfig::anyHost);
                    });
                }
                javalinConfig.jsonMapper(handleCustomJSONMapper(methods));
            }
        );
        this.addRoutes();
    }

    public String url() {
        return domain + ":" + port;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface AsService {
        boolean AnyHost() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface CustomJavalinConfig {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface CustomJSONMapper {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface RunOnInitialisation {
        boolean withServiceAsArg() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface RunOnPost {
        boolean withServiceAsArg() default false;
    }
}

/**
 * Contains All the Special Checks that throw exceptions during execution of a service
 */
class Checks {
    static void checkForNoArgs(Method method){
        if (method.getGenericParameterTypes().length != 0) {
            throw new MethodTakesNoArgumentsException(method.getName() + " must have no arguments");
        }
    }

    /**
     * Checks if a method has the correct return type.
     * @param method checked
     * @param type the method should return
     * @throws BadReturnTypeException if not correct
     */
    static void checkHasReturnType(Method method, Class<?> type) {
        if (!method.getReturnType().equals(type)){
            throw new BadReturnTypeException(
                    method.getName() + " has a bad return type. \n"
                            + "Expected: " +  type.getTypeName() + "\n"
                            + "Actual: " + method.getReturnType().getSimpleName()
            );
        }
    }

    static void checkClassAnnotation(Class<?> clazz){
        if (!clazz.isAnnotationPresent(Service.AsService.class)){
            throw new NotAServiceException(
                    clazz.getSimpleName() + " is not an Annotated with @AsService"
            );
        }
    }

    static void checkHasJavalinConfigAsArg(Method method){
        checkHasArgs(method, JavalinConfig.class);
    }

    static void checkHasArgs(Method method, Type ... types){
        Type[] params = method.getGenericParameterTypes();
        if (params.length != types.length){
            throw new ArgumentException(
                    method.getName() + " has not got enough parameters");
        }

        for (int i = 0; i < types.length; i++){
            if (!params[i].equals(types[i])){
                throw new ArgumentException(
                        method.getName() + " must have "+ types[i].getTypeName() +" as it's parameter, not " + params[i].getTypeName());
            }
        }


    }
}

class NotAServiceException extends RuntimeException {
    public NotAServiceException(String message){
        super(message);
    }
}

class MultipleJSONMapperMethodsException extends RuntimeException {
    public MultipleJSONMapperMethodsException(String message){
        super(message);
    }
}

class MethodTakesNoArgumentsException extends RuntimeException {
    public MethodTakesNoArgumentsException(String message){
        super(message);
    }
}

class ArgumentException extends RuntimeException {
    public ArgumentException(String message){
        super(message);
    }
}

class BadReturnTypeException extends RuntimeException {
    public BadReturnTypeException(String message){
        super(message);
    }
}

class AlreadyStartedException extends RuntimeException {
    public AlreadyStartedException(String message){
        super(message);
    }
}
class AlreadyStoppedException extends RuntimeException {
    public AlreadyStoppedException(String message){
        super(message);
    }
}



