package iudx.resource.server.apiserver.validation.types;

import static iudx.resource.server.apiserver.util.Constants.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.ext.web.api.RequestParameter;
import io.vertx.ext.web.api.validation.ParameterTypeValidator;
import io.vertx.ext.web.api.validation.ValidationException;

// TODO : find a better way to validate coordinates,
// current method works but not very efficient,
// it assumes in a , separated array odd index value will be a longitude and,
// even index value will be a latitude
public class CoordinatesTypeValidator {
  private static final Logger LOGGER = LogManager.getLogger(CoordinatesTypeValidator.class);

  private static final String LATITUDE_PATTERN =
      "^(\\+|-)?(?:90(?:(?:\\.0{1,6})?)|(?:[0-9]|[1-8][0-9])(?:(?:\\.[0-9]{1,6})?))$";
  private static final String LONGITUDE_PATTERN =
      "^(\\+|-)?(?:180(?:(?:\\.0{1,6})?)|(?:[0-9]|[1-9][0-9]|1[0-7][0-9])(?:(?:\\.[0-9]{1,6})?))$";
  private final int allowedMaxCoordinates = VALIDATION_ALLOWED_COORDINATES;
  private static final Pattern pattern = Pattern.compile("[\\w]+[^\\,]*(?:\\.*[\\w])");


  public ParameterTypeValidator create() {
    ParameterTypeValidator coordinatesValidator = new CoordinatesValidator();
    return coordinatesValidator;
  }

  class CoordinatesValidator implements ParameterTypeValidator {
    private DecimalFormat df = new DecimalFormat("#.######");

    private boolean isValidLatitude(String latitude) {
      try {
        Float latitudeValue = Float.parseFloat(latitude);
        if (!df.format(latitudeValue).matches(LATITUDE_PATTERN)) {
          throw ValidationException.ValidationExceptionFactory
              .generateNotMatchValidationException("invalid latitude value " + latitude);
        }
      } catch (Exception ex) {
        throw ValidationException.ValidationExceptionFactory
            .generateNotMatchValidationException("invalid latitude value " + latitude);
      }
      return true;
    }

    private boolean isValidLongitude(String longitude) {
      try {
        Float longitudeValue = Float.parseFloat(longitude);
        if (!df.format(longitudeValue).matches(LONGITUDE_PATTERN)) {
          throw ValidationException.ValidationExceptionFactory
              .generateNotMatchValidationException("invalid longitude value " + longitude);
        }
      } catch (Exception ex) {
        throw ValidationException.ValidationExceptionFactory
            .generateNotMatchValidationException("invalid longitude value " + longitude);
      }
      return true;
    }

    private boolean isPricisonLengthAllowed(String value) {
      boolean result = false;
      try {
        result = (new BigDecimal(value).scale() > VALIDATION_COORDINATE_PRECISION_ALLOWED);
      } catch (Exception ex) {
        throw ValidationException.ValidationExceptionFactory.generateNotMatchValidationException(
            "Invalid coordinate format.");
      }
      return result;
    }

    private boolean isValidCoordinates(String value) {
      String coordinates = value.replaceAll("\\[", "").replaceAll("\\]", "");
      String[] coordinatesArray = coordinates.split(",");
      boolean checkLongitudeFlag = false;
      for (String coordinate : coordinatesArray) {

        if (checkLongitudeFlag) {
          isValidLatitude(coordinate);
        } else {
          isValidLongitude(coordinate);
        }
        checkLongitudeFlag = !checkLongitudeFlag;
        if (isPricisonLengthAllowed(coordinate)) {
          throw ValidationException.ValidationExceptionFactory
              .generateNotMatchValidationException(
                  "invalid coordinate (only 6 digits to precision allowed)");
        }
      }
      return true;
    }

    private String getProbableGeomType(String coords) {
      String geom = null;
      if (coords.startsWith("[[[")) {
        geom = "polygon";
      } else if (coords.startsWith("[[")) {
        geom = "line";
      } else if (coords.startsWith("[")) {
        geom = "point";
      }
      return geom;
    }

    private boolean isValidCoordinateCount(String coordinates) {
      String geom = getProbableGeomType(coordinates);
      List<String> coordinatesList = getCoordinatesValues(coordinates);
      if (geom.equalsIgnoreCase("point")) {
        if (coordinatesList.size() != 2) {
          throw ValidationException.ValidationExceptionFactory.generateNotMatchValidationException(
              "Invalid number of coordinates given for point");
        }
      } else if (geom.equalsIgnoreCase("polygon")) {
        if (coordinatesList.size() > allowedMaxCoordinates * 2) {
          return false;
        }
      } else {
        if (coordinatesList.size() > allowedMaxCoordinates * 2) {
          return false;
        }
        // TODO : handle for line and bbox (since line and bbox share same [[][]] structure )
        return true;
      }
      return true;
    }

    private List<String> getCoordinatesValues(String coordinates) {
      Matcher matcher = pattern.matcher(coordinates);
      List<String> coordinatesValues =
          matcher.results()
              .map(MatchResult::group)
              .collect(Collectors.toList());
      return coordinatesValues;
    }

    @Override
    public RequestParameter isValid(String value) throws ValidationException {
      if (value.isBlank()) {
        throw ValidationException.ValidationExceptionFactory
            .generateNotMatchValidationException("Empty value not allowed for parameter.");
      }
      if (!isValidCoordinateCount(value)) {
        throw ValidationException.ValidationExceptionFactory.generateNotMatchValidationException(
            "Invalid numbers of coordinates supplied (Only 10 coordinates allowed for polygon and line & 1 coordinate for point)");
      }
      if (!isValidCoordinates(value)) {
        throw ValidationException.ValidationExceptionFactory.generateNotMatchValidationException(
            "invalid coordinate (only 6 digits to precision allowed)");
      }
      return RequestParameter.create(value);

    }

  }
}
