/*
By Tartiflette
 */
package org.scy;

import com.fs.starfarer.api.Global;

public class SCY_txt {
  private static final String SCY = "SCY";

  public static String txt(String id) {
    return Global.getSettings().getString(SCY, id);
  }

  public static String txt(String id, Object... objs) {
    return String.format(Global.getSettings().getString(SCY, id), objs);
  }
}
