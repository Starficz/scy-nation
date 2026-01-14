package org.scy;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.lazywizard.lazylib.*;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.List;
import java.util.*;

public class StarficzAIUtils {

    public static boolean isPointWithinMap(Vector2f point, float pad){
        CombatEngineAPI engine = Global.getCombatEngine();
        return (point.getX() < (engine.getMapWidth() / 2 - pad)) &&
                (point.getX() > (pad - engine.getMapWidth() / 2)) &&
                (point.getY() < (engine.getMapHeight() / 2 - pad)) &&
                (point.getY() > (pad - engine.getMapHeight() / 2));
    }

    public static Vector2f getBackingOffStrafePoint(ShipAPI ship){
        float secondsInFuture = 1f;
        float degreeDelta = 5f;
        Vector2f futureLocation= new Vector2f();
        Vector2f.add(ship.getLocation(), ship.getVelocity(), futureLocation);
        futureLocation.scale(secondsInFuture);
        List<Vector2f> potentialPoints = MathUtils.getPointsAlongCircumference(futureLocation, 1000f, (int) (360f/degreeDelta), 0);
        CollectionUtils.CollectionFilter<Vector2f> filterBorder = new CollectionUtils.CollectionFilter<Vector2f>() {
            @Override
            public boolean accept(Vector2f point) {
                return isPointWithinMap(point, 200);
            }
        };


        potentialPoints = CollectionUtils.filter(potentialPoints, filterBorder);
        Vector2f safestPoint = null;
        float furthestPointSumDistance = 0;
        List<ShipAPI> enemies = AIUtils.getNearbyEnemies(ship, 3000f);
        for (Vector2f potentialPoint : potentialPoints) {
            float currentPointSumDistance = 0;
            for(ShipAPI enemy : enemies){
                if(enemy.getHullSize() != ShipAPI.HullSize.FIGHTER)
                    currentPointSumDistance += MathUtils.getDistance(enemy, potentialPoint);
            }
            if(currentPointSumDistance > furthestPointSumDistance){
                furthestPointSumDistance = currentPointSumDistance;
                safestPoint = potentialPoint;
            }
        }

        return safestPoint;
    }

}
