package com.virjar.ratel.api.inspect.socket.observer;

import com.virjar.ratel.api.inspect.socket.SocketPackEvent;

public interface EventObserver {
    void onSocketPackageArrival(SocketPackEvent socketPackEvent);
}
