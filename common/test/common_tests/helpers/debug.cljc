(ns common-tests.helpers.debug
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [common-tests.helpers.ids-map :as thi]
   [cuerdas.core :as str]))

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

(defn- dump-page-shape
  [shape keys padding]
  (println (str/pad (str padding
                         (when (:main-instance shape) "{")
                         (or (thi/label (:id shape)) "<no-label>")
                         (when (:main-instance shape) "}")
                         (when keys
                           (str " [" (stringify-keys shape keys) "]")))
                    {:length 40 :type :right})
           (if (nil? (:shape-ref shape))
             (if (:component-root shape)
               (str "# [Component " (or (thi/label (:component-id shape)) "<no-label>") "]")
               "")
             (str/format "%s--> %s%s"
                         (cond (:component-root shape) "#"
                               (:component-id shape) "@"
                               :else "-")
                         (if (:component-root shape)
                           (str "[Component " (or (thi/label (:component-id shape)) "<no-label>") "] ")
                           "")
                         (or (thi/label (:shape-ref shape)) "<no-label>")))))

(defn dump-page
  "Dumps the layer tree of the page. Prints the label of each shape, and the specified keys.
   Example: (thd/dump-page (thf/current-page file) [:id :touched])"
  ([page keys]
   (dump-page page uuid/zero "" keys))
  ([page root-id padding keys]
   (let [lookupf (d/getf (:objects page))
         root-shape (lookupf root-id)
         shapes (map lookupf (:shapes root-shape))]
     (doseq [shape shapes]
       (dump-page-shape shape keys padding)
       (dump-page page (:id shape) (str padding "    ") keys)))))
