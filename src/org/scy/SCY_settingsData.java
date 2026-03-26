/*
By Tartiflette
 */
package org.scy;

import com.fs.starfarer.api.Global;
import org.magiclib.util.MagicSettings;

import java.util.ArrayList;
import java.util.List;

public class SCY_settingsData {

  //////////////////////////////
  //                          //
  //      SETTINGS DATA       //
  //                          //
  //////////////////////////////

  public static List<String> engineering_noncompatible = new ArrayList<>();

  public static void loadHullmodData() {
    engineering_noncompatible = MagicSettings.getList("SCY", "engineering_noncompatible");
  }
}
