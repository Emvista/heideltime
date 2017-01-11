package de.unihd.dbs.uima.annotator.heideltime.utilities;

import java.time.LocalDate;
import java.time.chrono.Era;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.unihd.dbs.uima.annotator.heideltime.resources.Language;
import de.unihd.dbs.uima.annotator.heideltime.resources.NormalizationManager;

/**
 * 
 * This class contains methods that rely on calendar functions to calculate data.
 * 
 * @author jannik stroetgen
 *
 */
public class DateCalculator {
	/** Class logger */
	private static final Logger LOG = LoggerFactory.getLogger(DateCalculator.class);

	// two formatters depending if BC or not
	static final DateTimeFormatter YEARFORMATTER = DateTimeFormatter.ofPattern("yyyy");
	static final DateTimeFormatter YEARFORMATTERBC = DateTimeFormatter.ofPattern("GGyyyy");

	static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	static final DateTimeFormatter WEEKFORMATTER = DateTimeFormatter.ofPattern("yyyy-w");

	private static LocalDate parseBC(String date) throws DateTimeParseException {
		if (date.length() == 0)
			throw new DateTimeParseException("Empty date string.", date, 0);
		return LocalDate.parse(date, Character.isDigit(date.charAt(0)) ? YEARFORMATTER : YEARFORMATTERBC);
	}

	public static String getXNextYear(String date, int x) {
		try {
			LocalDate d = parseBC(date).plusYears(x);
			return d.format((d.getEra() == IsoEra.CE) ? YEARFORMATTER : YEARFORMATTERBC);
		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return "";
		}
	}

	public static String getXNextDecade(String date, int x) {
		date = date + "0"; // deal with years not with centuries
		try {
			LocalDate d = parseBC(date).plusYears(10 * x);
			return d.format((d.getEra() == IsoEra.CE) ? YEARFORMATTER : YEARFORMATTERBC);
		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return "";
		}
	}

	public static String getXNextCentury(String date, int x) {
		date = date + "00"; // deal with years not with centuries

		try {
			LocalDate d = parseBC(date);
			Era oldEra = d.getEra();
			d = d.plusYears(x * 100);

			// check if new date is BC or AD for choosing formatter or formatterBC
			Era newEra = d.getEra();
			if (newEra == IsoEra.CE) {
				if (oldEra == IsoEra.BCE) {
					// -100 if from BC to AD
					d = d.minusYears(100); // FIXME: Why?
				}
				return d.format(YEARFORMATTER).substring(0, 2);
			} else {
				if (oldEra == IsoEra.CE) {
					// +100 if from AD to BC
					d = d.plusYears(100); // FIXME: Why?
				}
				return d.format(YEARFORMATTERBC).substring(0, 4);
			}

		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return "";
		}
	}

	/**
	 * get the x-next day of date.
	 * 
	 * @param date
	 *                given date to get new date from
	 * @param x
	 *                type of temporal event to search for
	 * @return
	 */
	public static String getXNextDay(String date, int x) {
		try {
			return LocalDate.parse(date, FORMATTER).plusDays(x).format(FORMATTER);
		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return "";
		}
	}

	/**
	 * get the x-next month of date
	 * 
	 * @param date
	 *                current date
	 * @param x
	 *                amount of months to go forward
	 * @return new month
	 */
	public static String getXNextMonth(String date, int x) {
		try {
			LocalDate d = parseBC(date).plusMonths(x);
			
			// FIXME: this is only year precision?
			// check if new date is BC or AD for choosing formatter or formatterBC
			return d.format((d.getEra() == IsoEra.CE) ? YEARFORMATTER : YEARFORMATTERBC);

		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return "";
		}
	}

	/**
	 * get the x-next week of date
	 * 
	 * @param date
	 *                current date
	 * @param x
	 *                amount of weeks to go forward
	 * @return new week
	 */
	public static String getXNextWeek(String date, int x, Language language) {
		NormalizationManager nm = NormalizationManager.getInstance(language, false);
		String date_no_W = date.replace("W", "");
		try {
			LocalDate d = LocalDate.parse(date_no_W, WEEKFORMATTER).plusWeeks(x);
			String newDate = d.format(WEEKFORMATTER);
			// TODO: use cheaper normalization?
			return newDate.substring(0, 4) + "-W" + nm.getFromNormNumber(newDate.substring(5));
		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return "";
		}
	}

	/**
	 * Get the weekday of date
	 * 
	 * Important: with the switch to Java 8, sunday became 7 rather than 1!
	 * 
	 * @param date
	 *                current date
	 * @return day of week
	 */
	public static int getWeekdayOfDate(String date) {
		try {
			return LocalDate.parse(date, FORMATTER).getDayOfWeek().getValue();
		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return 0;
		}
	}
	
	/**
	 * Get the week of date
	 * 
	 * @param date
	 *                current date
	 * @return week of year
	 */
	public static int getWeekOfDate(String date) {
		try {
			return LocalDate.parse(date, FORMATTER).get(WeekFields.ISO.weekOfWeekBasedYear());
		} catch (DateTimeParseException e) {
			LOG.error(e.getMessage(), e);
			return 0;
		}
	}
}
