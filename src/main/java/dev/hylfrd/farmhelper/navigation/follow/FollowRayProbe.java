package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.navigation.NavigationGoal;

/** Platform-independent ray evidence port; adapters must capture evidence on the client thread. */
@FunctionalInterface
public interface FollowRayProbe {
    FollowRayEvidence probe(NavigationGoal from, NavigationGoal to);
}
