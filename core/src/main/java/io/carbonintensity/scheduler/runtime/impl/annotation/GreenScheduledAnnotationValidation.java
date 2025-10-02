package io.carbonintensity.scheduler.runtime.impl.annotation;

import java.util.ArrayList;
import java.util.List;

import com.cronutils.utils.StringUtils;

import io.carbonintensity.scheduler.GreenScheduled;

/**
 * Utility class for validating {@link GreenScheduled} annotations.
 * <p>
 * This class provides a method to check the correctness of a {@link GreenScheduled} annotation
 * and return a list of validation errors if the configuration is invalid.
 * </p>
 *
 * <p>
 * If any validation rules are violated, a list of error messages is returned.
 * </p>
 *
 * @see GreenScheduled
 */
public class GreenScheduledAnnotationValidation {

    private GreenScheduledAnnotationValidation() {
    }

    static List<String> validateAndReturnValidationErrors(GreenScheduled annotation) {
        List<String> validationErrors = new ArrayList<>();
        if ((annotation.fixedWindow() == null && annotation.successive() == null) ||
                (!StringUtils.isEmpty(annotation.fixedWindow()) && !StringUtils.isEmpty(annotation.successive()))) {
            validationErrors.add("Either fixedWindow or successive must be specified");
        }

        if (StringUtils.isEmpty(annotation.carbonIntensityZone())) {
            validationErrors.add("Zone must be specified");
        }

        if (annotation.fixedWindow() != null && StringUtils.isEmpty(annotation.duration())) {
            validationErrors.add("Duration must be specified when fixedWindow is specified");
        }

        if (!StringUtils.isEmpty(annotation.dayOfMonth()) && !StringUtils.isEmpty(annotation.dayOfWeek())) {
            validationErrors.add("Day of month and day of week can not both be specified ");
        }

        return validationErrors;
    }

}
