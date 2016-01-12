(ns uxbox.time
  (:require [cljsjs.moment]))

(defn parse
  ([v]
   (js/moment v))
  ([v format]
   (case format
     :unix (js/moment.unix v)
     (js/moment v format))))

(defn iso
  [v]
  (.toISOString v))

(defn unix
  [v]
  (.unix v))

(defn now
  ([]
   (js/moment))
  ([format]
   (case format
     :unix (unix (now))
     :iso (iso (now)))))

(defn ago
  [time]
  (.fromNow (parse time)))

(defn day
  [time]
  (.calendar (parse time)
             nil
             #js {:sameDay "[Today]"
                  :sameElse "[Today]"
                  :lastDay "[Yesterday]"
                  :lastWeek "[Last] dddd"}))
