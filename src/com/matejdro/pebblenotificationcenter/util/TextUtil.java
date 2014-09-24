package com.matejdro.pebblenotificationcenter.util;

import com.matejdro.pebblenotificationcenter.PebbleNotificationCenter;
import java.util.HashMap;
import java.util.Map;


public class TextUtil
{
    public static String prepareString(String text)
    {
        return prepareString(text, 20);
    }

    public static String prepareString(String text, int length)
    {
        text = trimString(text, length, true);
        text = fixInternationalCharacters(text);

        if (RTLUtility.getInstance().isRTL(text))
        {
            text = RTLUtility.getInstance().format(text, 15);
        }

        return trimString(text, length, true);
    }


    public static String fixInternationalCharacters(String input)
    {
        HashMap<String, String> replacementTable = PebbleNotificationCenter.getInMemorySettings().getReplacingStrings();
        for (Map.Entry<String, String> e : replacementTable.entrySet())
        {
            input = input.replace(e.getKey(), e.getValue());
        }

        return input;
    }

    public static String trimString(String text)
    {
        return trimString(text, 20, true);
    }

    public static String trimString(String text, int length, boolean trailingElipsis)
    {
        if (text == null)
            return null;

        int targetLength = length;
        if (trailingElipsis)
        {
            targetLength -= 3;
        }

        if (text.getBytes().length > length)
        {
            if (text.length() > targetLength)
                text = text.substring(0, targetLength);

            while (text.getBytes().length > targetLength)
            {
                text = text.substring(0, text.length() - 1);
            }

            if (trailingElipsis)
                text = text + "...";

        }

        return text;
    }

    public static boolean isInteger(String text)
    {
        try
        {
            Integer.parseInt(text);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
