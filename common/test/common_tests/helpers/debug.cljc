(ns common-tests.helpers.debug
  (:require
   [app.common.uuid :as uuid]
   [common-tests.helpers.ids-map :as thi]))

(defn dump-shape
  "Dumps a shape, with each attribute in a line"
  [shape]
  (println "{")
  (doseq [[k v] (sort shape)]
    (when (some? v)
      (println (str "    " k " : " v))))
  (println "}"))

(defn- stringify-keys [m keys]
  (apply str (interpose ", " (map #(str % ": " (get m %)) keys))))

(defn dump-page
  "Dumps the layer tree of the page. Prints the label of each shape, and the specified keys.
   Example: (thd/dump-page (thf/current-page file) [:id :touched])"
  ([page keys]
   (dump-page page uuid/zero "" keys))
  ([page id padding keys]
   (let [objects (vals (:objects page))
         root-objects (filter #(and
                                (= (:parent-id %) id)
                                (not= (:id %) id))
                              objects)]
     (doseq [val root-objects]
       (println padding (thi/label (:id val))
                (when keys
                  (str "[" (stringify-keys val keys) "]")))
       (dump-page page (:id val) (str padding "    ") keys)))))
