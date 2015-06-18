(ns uxbox.time
  (:require cljsjs.moment))

(defn ago
  [time]
  (.fromNow (js/moment time)))

(defn day
  [time]
  (.calendar (js/moment. time)
             nil
             #js {:sameDay "[Today]"
                  :sameElse "[Today]"
                  :lastDay "[Yesterday]"
                  :lastWeek "[Last] dddd"}))
