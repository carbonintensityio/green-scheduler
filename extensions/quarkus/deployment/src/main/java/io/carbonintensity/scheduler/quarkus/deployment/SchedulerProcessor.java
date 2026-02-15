package io.carbonintensity.scheduler.quarkus.deployment;

import static io.quarkus.deployment.annotations.ExecutionTime.RUNTIME_INIT;
import static org.jboss.jandex.AnnotationTarget.Kind.METHOD;

import java.lang.reflect.Modifier;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.jandex.Type.Kind;
import org.jboss.logging.Logger;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.quarkus.common.runtime.DefaultInvoker;
import io.carbonintensity.scheduler.quarkus.common.runtime.MutableScheduledMethod;
import io.carbonintensity.scheduler.quarkus.common.runtime.SchedulerContext;
import io.carbonintensity.scheduler.quarkus.common.runtime.util.SchedulerUtils;
import io.carbonintensity.scheduler.quarkus.factory.SchedulerProducer;
import io.carbonintensity.scheduler.quarkus.runtime.QuarkusScheduler;
import io.carbonintensity.scheduler.quarkus.runtime.SchedulerRecorder;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AutoAddScopeBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.TransformedAnnotationsBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem.BeanClassAnnotationExclusion;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanDeploymentValidator;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.AnnotationProxyBuildItem;
import io.quarkus.deployment.builditem.ConfigDescriptionBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceDirectoryBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.gizmo.CatchBlockCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.DescriptorUtils;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.gizmo.TryBlock;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.util.HashUtil;

public class SchedulerProcessor {

    private static final Logger LOGGER = Logger.getLogger(SchedulerProcessor.class);

    static final Type SCHEDULED_EXECUTION_TYPE = Type.create(DotName.createSimple(ScheduledExecution.class.getName()),
            Kind.CLASS);

    static final String INVOKER_SUFFIX = "_ScheduledInvoker";
    static final String NESTED_SEPARATOR = "$_";

    @BuildStep
    NativeImageResourceDirectoryBuildItem nativeImageResourceBuildItem() {
        return new NativeImageResourceDirectoryBuildItem("fallback");
    }

    @BuildStep
    void registerQuarkusSchedulerDisableProperty(BuildProducer<ConfigDescriptionBuildItem> configDescriptions) {
        configDescriptions.produce(new ConfigDescriptionBuildItem(
                "quarkus.scheduler.enabled",
                "true",
                "Enables the Quarkus scheduler component. When disabled, the Green Scheduler stays off as well.",
                Boolean.class.getName(),
                List.of("true", "false"),
                ConfigPhase.BUILD_AND_RUN_TIME_FIXED));
    }

    @BuildStep
    void beans(BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        additionalBeans.produce(new AdditionalBeanBuildItem(QuarkusScheduler.class));
        additionalBeans.produce(new AdditionalBeanBuildItem(SchedulerProducer.class));
    }

    @BuildStep
    AutoAddScopeBuildItem autoAddScope() {
        // We add @Singleton to any bean class that has no scope annotation and declares at least one non-static method annotated with @GreenScheduled
        return AutoAddScopeBuildItem.builder()
                .anyMethodMatches(m -> !Modifier.isStatic(m.flags())
                        && (m.hasAnnotation(SchedulerDotNames.SCHEDULED_NAME)
                                || m.hasAnnotation(SchedulerDotNames.SCHEDULES_NAME)))
                .defaultScope(BuiltinScope.SINGLETON)
                .reason("Found non-static scheduled business methods").build();
    }

    @BuildStep
    void collectScheduledMethods(BeanArchiveIndexBuildItem beanArchives, BeanDiscoveryFinishedBuildItem beanDiscovery,
            TransformedAnnotationsBuildItem transformedAnnotations,
            BuildProducer<ScheduledBusinessMethodItem> scheduledBusinessMethods) {

        // First collect static scheduled methods
        List<AnnotationInstance> schedules = new ArrayList<>(
                beanArchives.getIndex().getAnnotations(SchedulerDotNames.SCHEDULED_NAME));
        for (AnnotationInstance annotationInstance : beanArchives.getIndex().getAnnotations(SchedulerDotNames.SCHEDULES_NAME)) {
            for (AnnotationInstance scheduledInstance : annotationInstance.value().asNestedArray()) {
                // We need to set the target of the containing instance
                schedules.add(AnnotationInstance.create(scheduledInstance.name(), annotationInstance.target(),
                        scheduledInstance.values()));
            }
        }
        for (AnnotationInstance annotationInstance : schedules) {
            if (annotationInstance.target().kind() != METHOD) {
                continue; // This should never happen as the annotation has @Target(METHOD)
            }
            MethodInfo method = annotationInstance.target().asMethod();
            ClassInfo declaringClass = method.declaringClass();
            if (!Modifier.isStatic(method.flags())
                    && (Modifier.isAbstract(declaringClass.flags()) || declaringClass.isInterface())) {
                throw new IllegalStateException(String.format(
                        "Non-static @GreenScheduled methods may not be declared on abstract classes and interfaces: %s() declared on %s",
                        method.name(), declaringClass.name()));
            }
            if (Modifier.isStatic(method.flags())) {
                scheduledBusinessMethods.produce(new ScheduledBusinessMethodItem(null, method, schedules));
                LOGGER.debugf("Found scheduled static method %s declared on %s", method, declaringClass.name());
            }
        }

        // Then collect all business methods annotated with @GreenScheduled
        for (BeanInfo bean : beanDiscovery.beanStream().classBeans()) {
            collectScheduledMethods(transformedAnnotations, bean,
                    bean.getTarget().get().asClass(),
                    scheduledBusinessMethods);
        }
    }

    private void collectScheduledMethods(TransformedAnnotationsBuildItem transformedAnnotations, BeanInfo bean,
            ClassInfo beanClass, BuildProducer<ScheduledBusinessMethodItem> scheduledBusinessMethods) {

        for (MethodInfo method : beanClass.methods()) {
            if (Modifier.isStatic(method.flags())) {
                // Ignore static methods
                continue;
            }
            List<AnnotationInstance> schedules = null;
            AnnotationInstance scheduledAnnotation = transformedAnnotations.getAnnotation(method,
                    SchedulerDotNames.SCHEDULED_NAME);
            if (scheduledAnnotation != null) {
                schedules = List.of(scheduledAnnotation);
            } else {
                AnnotationInstance schedulesAnnotation = transformedAnnotations.getAnnotation(method,
                        SchedulerDotNames.SCHEDULES_NAME);
                if (schedulesAnnotation != null) {
                    schedules = new ArrayList<>();
                    for (AnnotationInstance scheduledInstance : schedulesAnnotation.value().asNestedArray()) {
                        // We need to set the target of the containing instance
                        schedules.add(AnnotationInstance.create(scheduledInstance.name(), schedulesAnnotation.target(),
                                scheduledInstance.values()));
                    }
                }
            }
            if (schedules != null) {
                boolean nonBlocking = transformedAnnotations.hasAnnotation(method, SchedulerDotNames.NON_BLOCKING);
                scheduledBusinessMethods
                        .produce(new ScheduledBusinessMethodItem(bean, method, schedules, nonBlocking));
                LOGGER.debugf("Found scheduled business method %s declared on %s", method, bean);
            }
        }
    }

    @BuildStep
    void validateScheduledBusinessMethods(List<ScheduledBusinessMethodItem> scheduledMethods,
            ValidationPhaseBuildItem validationPhase, BuildProducer<ValidationErrorBuildItem> validationErrors,
            BeanArchiveIndexBuildItem beanArchiveIndex) {
        List<Throwable> errors = new ArrayList<>();
        Map<String, AnnotationInstance> encounteredIdentities = new HashMap<>();
        Set<String> methodDescriptions = new HashSet<>();

        for (ScheduledBusinessMethodItem scheduledMethod : scheduledMethods) {
            if (!methodDescriptions.add(scheduledMethod.getMethodDescription())) {
                errors.add(new IllegalStateException(
                        "Multiple @GreenScheduled methods of the same name declared on the same class: "
                                + scheduledMethod.getMethodDescription()));
                continue;
            }
            MethodInfo method = scheduledMethod.getMethod();
            if (Modifier.isAbstract(method.flags())) {
                errors.add(new IllegalStateException("@GreenScheduled method must not be abstract: "
                        + scheduledMethod.getMethodDescription()));
                continue;
            }
            if (Modifier.isPrivate(method.flags())) {
                errors.add(new IllegalStateException("@GreenScheduled method must not be private: "
                        + scheduledMethod.getMethodDescription()));
                continue;
            }

            // Validate method params and return type
            List<Type> params = method.parameterTypes();
            int maxParamSize = 1;
            if (params.size() > maxParamSize
                    || (params.size() == maxParamSize && !params.get(0).equals(SCHEDULED_EXECUTION_TYPE))) {
                errors.add(new IllegalStateException(String.format(
                        "Invalid scheduled business method parameters %s [method: %s, bean: %s]", params,
                        method, scheduledMethod.getBean())));
            }
            if (!isValidReturnType(method)) {

                errors.add(new IllegalStateException(
                        String.format(
                                "Scheduled business method must return void, CompletionStage<Void> or Uni<Void> [method: %s, bean: %s]",
                                method, scheduledMethod.getBean())));

            }
            // Validate cron() expressions
            CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
            for (AnnotationInstance scheduled : scheduledMethod.getSchedules()) {
                Throwable error = validateScheduled(parser, scheduled, encounteredIdentities, validationPhase.getContext(),
                        beanArchiveIndex.getIndex());
                if (error != null) {
                    errors.add(error);
                }
            }
        }

        if (!errors.isEmpty()) {
            validationErrors.produce(new ValidationErrorBuildItem(errors));
        }
    }

    private boolean isValidReturnType(MethodInfo method) {
        Type returnType = method.returnType();
        if (returnType.kind() == Kind.VOID) {
            return true;
        }
        if (SchedulerDotNames.COMPLETION_STAGE.equals(returnType.name())
                && returnType.asParameterizedType().arguments().get(0).name().equals(SchedulerDotNames.VOID)) {
            return true;
        }
        if (SchedulerDotNames.UNI.equals(returnType.name())
                && returnType.asParameterizedType().arguments().get(0).name().equals(SchedulerDotNames.VOID)) {
            return true;
        }
        return false;
    }

    @BuildStep
    public List<UnremovableBeanBuildItem> unremovableBeans() {
        // Beans annotated with @GreenScheduled should never be removed
        return List.of(new UnremovableBeanBuildItem(new BeanClassAnnotationExclusion(SchedulerDotNames.SCHEDULED_NAME)),
                new UnremovableBeanBuildItem(new BeanClassAnnotationExclusion(SchedulerDotNames.SCHEDULES_NAME)));
    }

    @BuildStep
    @Record(RUNTIME_INIT)
    public FeatureBuildItem build(BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            SchedulerRecorder recorder, List<ScheduledBusinessMethodItem> scheduledMethods,
            BuildProducer<GeneratedClassBuildItem> generatedClasses, BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            AnnotationProxyBuildItem annotationProxy) {

        List<MutableScheduledMethod> scheduledMetadata = new ArrayList<>();
        ClassOutput classOutput = new GeneratedClassGizmoAdaptor(generatedClasses, (Function<String, String>) name -> {
            // org/acme/Foo_ScheduledInvoker_run_0000 -> org.acme.Foo
            int idx = name.indexOf(INVOKER_SUFFIX);
            if (idx != -1) {
                name = name.substring(0, idx);
            }
            if (name.contains(NESTED_SEPARATOR)) {
                name = name.replace(NESTED_SEPARATOR, "$");
            }
            return name;
        });

        for (ScheduledBusinessMethodItem scheduledMethod : scheduledMethods) {
            MutableScheduledMethod metadata = new MutableScheduledMethod();
            String invokerClass = generateInvoker(scheduledMethod, classOutput);
            reflectiveClass.produce(ReflectiveClassBuildItem.builder(invokerClass).constructors().methods().fields().build());
            metadata.setInvokerClassName(invokerClass);
            List<GreenScheduled> schedules = new ArrayList<>();
            for (AnnotationInstance scheduled : scheduledMethod.getSchedules()) {
                schedules.add(annotationProxy.builder(scheduled, GreenScheduled.class).build(classOutput));
            }
            metadata.setSchedules(schedules);
            metadata.setDeclaringClassName(scheduledMethod.getMethod().declaringClass().toString());
            metadata.setMethodName(scheduledMethod.getMethod().name());
            scheduledMetadata.add(metadata);
        }

        syntheticBeans.produce(SyntheticBeanBuildItem.configure(SchedulerContext.class).setRuntimeInit()
                .supplier(recorder.createContext(scheduledMetadata))
                .done());

        return new FeatureBuildItem("green-scheduler");
    }

    private String generateInvoker(ScheduledBusinessMethodItem scheduledMethod, ClassOutput classOutput) {

        BeanInfo bean = scheduledMethod.getBean();
        MethodInfo method = scheduledMethod.getMethod();
        boolean isStatic = Modifier.isStatic(method.flags());
        ClassInfo implClazz = isStatic ? method.declaringClass() : bean.getImplClazz();

        String baseName;
        if (implClazz.enclosingClass() != null) {
            baseName = DotNames.simpleName(implClazz.enclosingClass()) + NESTED_SEPARATOR
                    + DotNames.simpleName(implClazz);
        } else {
            baseName = DotNames.simpleName(implClazz.name());
        }
        StringBuilder sigBuilder = new StringBuilder();
        sigBuilder.append(method.name()).append("_").append(method.returnType().name().toString());
        for (Type i : method.parameterTypes()) {
            sigBuilder.append(i.name().toString());
        }
        String generatedName = DotNames.internalPackageNameWithTrailingSlash(implClazz.name()) + baseName
                + INVOKER_SUFFIX + "_" + method.name() + "_"
                + HashUtil.sha1(sigBuilder.toString());

        ClassCreator invokerCreator = ClassCreator.builder().classOutput(classOutput).className(generatedName)
                .superClass(DefaultInvoker.class.getName())
                .build();

        if (scheduledMethod.isNonBlocking()) {
            MethodCreator isBlocking = invokerCreator.getMethodCreator("isBlocking", boolean.class);
            isBlocking.returnValue(isBlocking.load(false));
        }
        // The descriptor is: CompletionStage invoke(ScheduledExecution execution)
        MethodCreator invoke = invokerCreator.getMethodCreator("invokeBean", CompletionStage.class, ScheduledExecution.class);

        // Use a try-catch block and return failed future if an exception is thrown
        TryBlock tryBlock = invoke.tryBlock();
        CatchBlockCreator catchBlock = tryBlock.addCatch(Throwable.class);

        catchBlock.returnValue(catchBlock.invokeStaticMethod(
                MethodDescriptor.ofMethod(CompletableFuture.class, "failedStage", CompletionStage.class, Throwable.class),
                catchBlock.getCaughtException()));

        String returnTypeStr = DescriptorUtils.typeToString(method.returnType());
        ResultHandle res;
        if (isStatic) {
            if (implClazz.isInterface()) {
                if (method.parameterTypes().isEmpty()) {
                    res = tryBlock.invokeStaticInterfaceMethod(
                            MethodDescriptor.ofMethod(implClazz.name().toString(), method.name(), returnTypeStr));
                } else {
                    res = tryBlock.invokeStaticInterfaceMethod(
                            MethodDescriptor.ofMethod(implClazz.name().toString(), method.name(), returnTypeStr,
                                    ScheduledExecution.class),
                            tryBlock.getMethodParam(0));
                }
            } else {
                if (method.parameterTypes().isEmpty()) {
                    res = tryBlock.invokeStaticMethod(
                            MethodDescriptor.ofMethod(implClazz.name().toString(), method.name(), returnTypeStr));
                } else {
                    res = tryBlock.invokeStaticMethod(
                            MethodDescriptor.ofMethod(implClazz.name().toString(), method.name(), returnTypeStr,
                                    ScheduledExecution.class),
                            tryBlock.getMethodParam(0));
                }
            }
        } else {
            // InjectableBean<Foo> bean = Arc.container().bean("foo1");
            // InstanceHandle<Foo> handle = Arc.container().instance(bean);
            // handle.get().ping();
            ResultHandle containerHandle = tryBlock
                    .invokeStaticMethod(MethodDescriptor.ofMethod(Arc.class, "container", ArcContainer.class));
            ResultHandle beanHandle = tryBlock.invokeInterfaceMethod(
                    MethodDescriptor.ofMethod(ArcContainer.class, "bean", InjectableBean.class, String.class),
                    containerHandle, tryBlock.load(bean.getIdentifier()));
            ResultHandle instanceHandle = tryBlock.invokeInterfaceMethod(
                    MethodDescriptor.ofMethod(ArcContainer.class, "instance", InstanceHandle.class, InjectableBean.class),
                    containerHandle, beanHandle);
            ResultHandle beanInstanceHandle = tryBlock
                    .invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "get", Object.class),
                            instanceHandle);

            if (method.parameterTypes().isEmpty()) {
                res = tryBlock.invokeVirtualMethod(
                        MethodDescriptor.ofMethod(implClazz.name().toString(), method.name(), returnTypeStr),
                        beanInstanceHandle);
            } else {
                res = tryBlock.invokeVirtualMethod(
                        MethodDescriptor.ofMethod(implClazz.name().toString(), method.name(), returnTypeStr,
                                ScheduledExecution.class),
                        beanInstanceHandle, tryBlock.getMethodParam(0));
            }

            // handle.destroy() - destroy dependent instance afterwards
            if (BuiltinScope.DEPENDENT.is(bean.getScope())) {
                tryBlock.invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "destroy", void.class),
                        instanceHandle);
            }
        }

        if (res == null) {
            // If the return type is void then return a new completed stage
            res = tryBlock.invokeStaticMethod(
                    MethodDescriptor.ofMethod(CompletableFuture.class, "completedStage", CompletionStage.class, Object.class),
                    tryBlock.loadNull());
        } else if (method.returnType().name().equals(SchedulerDotNames.UNI)) {
            // Subscribe to the returned Uni
            res = tryBlock.invokeInterfaceMethod(MethodDescriptor.ofMethod(SchedulerDotNames.UNI.toString(),
                    "subscribeAsCompletionStage", CompletableFuture.class), res);
        }

        tryBlock.returnValue(res);
        invokerCreator.close();
        return generatedName.replace('/', '.');
    }

    private Throwable validateScheduled(CronParser parser, AnnotationInstance schedule,
            Map<String, AnnotationInstance> encounteredIdentities, BeanDeploymentValidator.ValidationContext validationContext,
            IndexView index) {
        MethodInfo method = schedule.target().asMethod();
        AnnotationValue cronValue = schedule.value("cron");
        if (cronValue != null && !cronValue.asString().trim().isEmpty()) {
            String cron = cronValue.asString().trim();
            if (!SchedulerUtils.isConfigValue(cron)) {
                try {
                    parser.parse(cron).validate();
                } catch (IllegalArgumentException e) {
                    return new IllegalStateException(errorMessage("Invalid cron() expression", schedule, method), e);
                }

            }
            // Validate the time carbonIntensityZone ID
            AnnotationValue timeZoneValue = schedule.value("timeZone");
            if (timeZoneValue != null) {
                String timeZone = timeZoneValue.asString();
                if (!SchedulerUtils.isConfigValue(timeZone) && !timeZone.equals("")) {
                    try {
                        ZoneId.of(timeZone);
                    } catch (Exception e) {
                        return new IllegalStateException(errorMessage("Invalid timeZone()", schedule, method), e);
                    }
                }
            }
        }

        AnnotationValue identityValue = schedule.value("identity");
        if (identityValue != null) {
            String identity = SchedulerUtils.lookUpPropertyValue(identityValue.asString());
            AnnotationInstance previousInstanceWithSameIdentity = encounteredIdentities.get(identity);
            if (previousInstanceWithSameIdentity != null) {
                String message = String.format("The identity: \"%s\" on: %s is not unique and it has already bean used by : %s",
                        identity, schedule, previousInstanceWithSameIdentity);
                return new IllegalStateException(message);
            } else {
                encounteredIdentities.put(identity, schedule);
            }
        }

        AnnotationValue skipExecutionIfValue = schedule.value("skipExecutionIf");
        if (skipExecutionIfValue != null) {
            DotName skipPredicate = skipExecutionIfValue.asClass().name();
            if (SchedulerDotNames.SKIP_NEVER_NAME.equals(skipPredicate)) {
                return null;
            }
            List<BeanInfo> beans = validationContext.beans().withBeanType(skipPredicate).collect();
            if (beans.size() > 1) {
                String message = String.format(
                        "There must be exactly one bean that matches the skip predicate: \"%s\" on: %s; beans: %s",
                        skipPredicate, schedule, beans);
                return new IllegalStateException(message);
            } else if (beans.isEmpty()) {
                ClassInfo skipPredicateClass = index.getClassByName(skipPredicate);
                if (skipPredicateClass != null) {
                    MethodInfo noArgsConstructor = skipPredicateClass.method("<init>");
                    if (noArgsConstructor == null || !Modifier.isPublic(noArgsConstructor.flags())) {
                        return new IllegalStateException(
                                "The skip predicate class must declare a public no-args constructor: " + skipPredicateClass);
                    }
                }
            }
        }

        return null;
    }

    private static String errorMessage(String base, AnnotationInstance scheduled, MethodInfo method) {
        return String.format("%s: %s declared on %s#%s()", base, scheduled, method.declaringClass().name(), method.name());
    }

    @BuildStep
    UnremovableBeanBuildItem unremoveableSkipPredicates() {
        return new UnremovableBeanBuildItem(new UnremovableBeanBuildItem.BeanTypeExclusion(SchedulerDotNames.SKIP_PREDICATE));
    }

    @BuildStep
    ReflectiveClassBuildItem reflectiveClasses() {
        return ReflectiveClassBuildItem.builder("com.github.benmanes.caffeine.cache.SSA").build();
    }
}
