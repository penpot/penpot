;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns app.util.geom.path
  (:require
   [app.common.data :as cd]
   [app.common.data :as cd]
   [app.common.geom.point :as gpt]
   [app.util.a2c :refer [a2c]]
   [app.util.data :as d]
   [app.util.geom.path-impl-simplify :as impl-simplify]
   [cuerdas.core :as str]))

(defn simplify
  ([points]
   (simplify points 0.1))
  ([points tolerance]
   (let [points (into-array points)]
     (into [] (impl-simplify/simplify points tolerance true)))))

;;
(def commands-regex #"(?i)[a-z][^a-z]*")

;; Matches numbers for path values allows values like... -.01, 10, +12.22
;; 0 and 1 are special because can refer to flags
(def num-regex #"[+-]?(\d+(\.\d+)?|\.\d+)")

(def flag-regex #"[01]")

(defn fix-dot-number [val]
  (if (str/starts-with? val ".")
    (str "0" val)
    val))

(defn extract-params [cmd-str extract-commands]
  (loop [result []
         extract-idx 0
         current {}
         remain (-> cmd-str (subs 1) (str/trim))]

    (let [[param type] (nth extract-commands extract-idx)
          regex (case type
                  :flag     flag-regex
                  #_:number num-regex)
          match (re-find regex remain)]

      (if match
        (let [value (-> match first fix-dot-number d/read-string)
              remain (str/replace-first remain regex "")
              current (assoc current param value)
              extract-idx (inc extract-idx)
              [result current extract-idx]
              (if (>=  extract-idx (count extract-commands))
                [(conj result current) {} 0]
                [result current extract-idx])]
          (recur result
                 extract-idx
                 current
                 remain))
        (cond-> result
          (not (empty? current)) (conj current))))))

(defn command->param-list [{:keys [command params]}]
  (case command
    (:move-to :line-to :smooth-quadratic-bezier-curve-to)
    (let [{:keys [x y]} params] [x y])

    :close-path
    []

    (:line-to-horizontal :line-to-vertical)
    (let [{:keys [value]} params] [value])

    :curve-to
    (let [{:keys [c1x c1y c2x c2y x y]} params] [c1x c1y c2x c2y x y])

    (:smooth-curve-to :quadratic-bezier-curve-to)
    (let [{:keys [cx cy x y]} params] [cx cy x y])

    :elliptical-arc
    (let [{:keys [rx ry x-axis-rotation large-arc-flag sweep-flag x y]} params]
      [rx ry x-axis-rotation large-arc-flag sweep-flag x y])))

;; Path specification
;; https://www.w3.org/TR/SVG11/paths.html
(defmulti parse-command (comp str/upper first))

(defmethod parse-command "M" [cmd]
  (let [relative (str/starts-with? cmd "m")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :move-to
       :relative relative
       :params params})))

(defmethod parse-command "Z" [cmd]
  [{:command :close-path}])

(defmethod parse-command "L" [cmd]
  (let [relative (str/starts-with? cmd "l")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :line-to
       :relative relative
       :params params})))

(defmethod parse-command "H" [cmd]
  (let [relative (str/starts-with? cmd "h")
        param-list (extract-params cmd [[:value :number]])]
    (for [params param-list]
      {:command :line-to-horizontal
       :relative relative
       :params params})))

(defmethod parse-command "V" [cmd]
  (let [relative (str/starts-with? cmd "v")
        param-list (extract-params cmd [[:value :number]])]
    (for [params param-list]
      {:command :line-to-vertical
       :relative relative
       :params params})))

(defmethod parse-command "C" [cmd]
  (let [relative (str/starts-with? cmd "c")
        param-list (extract-params cmd [[:c1x :number]
                                        [:c1y :number]
                                        [:c2x :number]
                                        [:c2y :number]
                                        [:x   :number]
                                        [:y   :number]])
        ]
    (for [params param-list]
      {:command :curve-to
       :relative relative
       :params params})))

(defmethod parse-command "S" [cmd]
  (let [relative (str/starts-with? cmd "s")
        param-list (extract-params cmd [[:c1x :number]
                                        [:c2y :number]
                                        [:x  :number]
                                        [:y  :number]])]
    (for [params param-list]
      {:command :smooth-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "Q" [cmd]
  (let [relative (str/starts-with? cmd "s")
        param-list (extract-params cmd [[:c1x :number]
                                        [:c1y :number]
                                        [:x   :number]
                                        [:y   :number]])]
    (for [params param-list]
      {:command :quadratic-bezier-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "T" [cmd]
  (let [relative (str/starts-with? cmd "t")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :smooth-quadratic-bezier-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "A" [cmd]
  (let [relative (str/starts-with? cmd "a")
        param-list (extract-params cmd [[:rx :number]
                                        [:ry :number]
                                        [:x-axis-rotation :number]
                                        [:large-arc-flag :flag]
                                        [:sweep-flag :flag]
                                        [:x :number]
                                        [:y :number]])]
    (for [params param-list]
      {:command :elliptical-arc
       :relative relative
       :params params})))

(defn command->string [{:keys [command relative params] :as entry}]
  (let [command-str (case command
                      :move-to "M"
                      :close-path "Z"
                      :line-to "L"
                      :line-to-horizontal "H"
                      :line-to-vertical "V"
                      :curve-to "C"
                      :smooth-curve-to "S"
                      :quadratic-bezier-curve-to "Q"
                      :smooth-quadratic-bezier-curve-to "T"
                      :elliptical-arc "A")
        command-str (if relative (str/lower command-str) command-str)
        param-list (command->param-list entry)]
    (str/fmt "%s%s" command-str (str/join " " param-list))))

(defn cmd-pos [{:keys [params]}]
  (when (and (contains? params :x)
             (contains? params :y))
    (gpt/point params)))

(defn arc->beziers [prev command]
  (let [to-command
        (fn [[_ _ c1x c1y c2x c2y x y]]
          {:command :curve-to
           :relative (:relative command)
           :params {:c1x c1x :c1y c1y
                    :c2x c2x :c2y c2y
                    :x   x   :y   y}})

        {from-x :x from-y :y} (:params prev)
        {:keys [rx ry x-axis-rotation large-arc-flag sweep-flag x y]} (:params command)
        result (a2c from-x from-y x y large-arc-flag sweep-flag rx ry x-axis-rotation)]
    
    (mapv to-command result)))

(defn simplify-commands
  "Removes some commands and convert relative to absolute coordinates"
  [commands]

  (let [simplify-command
        (fn [[pos result] [command prev]]
          (let [command
                (cond-> command
                  (= :line-to-horizontal (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (assoc-in [:params :x] (get-in command [:params :value]))
                      (assoc-in [:params :y] (if (:relative command) 0 (:y pos))))

                  (= :line-to-vertical (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (assoc-in [:params :y] (get-in command [:params :value]))
                      (assoc-in [:params :x] (if (:relative command) 0 (:x pos))))

                  (:relative command)
                  (-> (assoc :relative false)
                      (cd/update-in-when [:params :x] + (:x pos))
                      (cd/update-in-when [:params :y] + (:y pos))))
                
                result #_(conj result command)
                (if (= :elliptical-arc (:command command))
                         (cd/concat result (arc->beziers prev command))
                         (conj result command))]
            [(cmd-pos command) result]))

        start (first commands)
        start-pos (cmd-pos start)]

    
    (->> (map vector (rest commands) commands)
         (reduce simplify-command [start-pos [start]])
         (second))))

(defn path->content [string]
  (let [clean-string (-> string
                         (str/trim)
                         ;; Change "commas" for spaces
                         (str/replace #"," " ")
                         ;; Remove all consecutive spaces
                         (str/replace #"\s+" " "))
        commands (re-seq commands-regex clean-string)]
    (-> (mapcat parse-command commands)
        (simplify-commands))))

(defn content->path [content]
  (->> content
       (map command->string)
       (str/join "")))

(defn make-curve-params
  ([point]
   (make-curve-params point point point))

  ([point handler] (make-curve-params point handler point))

  ([point h1 h2]
   {:x (:x point)
    :y (:y point)
    :c1x (:x h1)
    :c1y (:y h1)
    :c2x (:x h2)
    :c2y (:y h2)}))

(defn opposite-handler
  "Calculates the coordinates of the opposite handler"
  [point handler]
  (let [phv (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate phv))))

(defn opposite-handler-keep-distance
  "Calculates the coordinates of the opposite handler but keeping the old distance"
  [point handler old-opposite]
  (let [old-distance (gpt/distance point old-opposite)
        phv (gpt/to-vec point handler)
        phv2 (gpt/multiply
              (gpt/unit (gpt/negate phv))
              (gpt/point old-distance))]
    (gpt/add point phv2)))

(defn apply-content-modifiers [content modifiers]
  (letfn [(apply-to-index [content [index params]]
            (if (contains? content index)
              (cond-> content
                (and
                 (or (:c1x params) (:c1y params) (:c2x params) (:c2y params))
                 (= :line-to (get-in content [index :params :command])))
                (-> (assoc-in [index :command] :curve-to)
                    (assoc-in [index :params] :curve-to) (make-curve-params
                                                          (get-in content [index :params])
                                                          (get-in content [(dec index) :params])))

                (:x params) (update-in [index :params :x] + (:x params))
                (:y params) (update-in [index :params :y] + (:y params))

                (:c1x params) (update-in [index :params :c1x] + (:c1x params))
                (:c1y params) (update-in [index :params :c1y] + (:c1y params))

                (:c2x params) (update-in [index :params :c2x] + (:c2x params))
                (:c2y params) (update-in [index :params :c2y] + (:c2y params)))
              content))]
    (let [content (if (vector? content) content (into [] content))]
      (reduce apply-to-index content modifiers))))

(defn command->point [command]
  (when-not (nil? command)
    (let [{{:keys [x y]} :params} command]
      (gpt/point x y))))

(defn content->points [content]
  (->> content
       (map #(when (-> % :params :x) (gpt/point (-> % :params :x) (-> % :params :y))))
       (remove nil?)
       (into [])))

(defn get-handler [{:keys [params] :as command} prefix]
  (let [cx (d/prefix-keyword prefix :x)
        cy (d/prefix-keyword prefix :y)]
    (when (and command
               (contains? params cx)
               (contains? params cy))
      (gpt/point (get params cx)
                 (get params cy)))))

(defn content->handlers
  "Retrieve a map where for every point will retrieve a list of
  the handlers that are associated with that point.
  point -> [[index, prefix]]"
  [content]
  (->> (d/with-prev content)
       (d/enumerate)

       (mapcat (fn [[index [cur-cmd pre-cmd]]]
                 (if (and pre-cmd (= :curve-to (:command cur-cmd)))
                   (let [cur-pos (command->point cur-cmd)
                         pre-pos (command->point pre-cmd)]
                     (-> [[pre-pos [index :c1]]
                          [cur-pos [index :c2]]]))
                   [])))

       (group-by first)
       (cd/mapm #(mapv second %2))))

(defn opposite-index
  "Calculate sthe opposite index given a prefix and an index"
  [content index prefix]
  (let [point (if (= prefix :c2)
                (command->point (nth content index))
                (command->point (nth content (dec index))))

        handlers (-> (content->handlers content)
                     (get point))

        opposite-prefix (if (= prefix :c1) :c2 :c1)]
    (when (<= (count handlers) 2)
      (->> handlers
           (d/seek (fn [[index prefix]] (= prefix opposite-prefix)))
           (first)))))

(defn remove-line-curves
  "Remove all curves that have both handlers in the same position that the
  beggining and end points. This makes them really line-to commands"
  [content]
  (let [with-prev (d/enumerate (d/with-prev content))
        process-command
        (fn [content [index [command prev]]]

          (let [cur-point (command->point command)
                pre-point (command->point prev)
                handler-c1 (get-handler command :c1)
                handler-c2 (get-handler command :c2)]
            (if (and (= :curve-to (:command command))
                     (= cur-point handler-c2)
                     (= pre-point handler-c1))
              (assoc content index {:command :line-to
                                    :params cur-point})
              content)))]

    (reduce process-command content with-prev)))

(defn make-corner-point
  "Changes the content to make a point a 'corner'"
  [content point]
  (let [handlers (-> (content->handlers content)
                     (get point))
        change-content
        (fn [content [index prefix]]
          (let [cx (d/prefix-keyword prefix :x)
                cy (d/prefix-keyword prefix :y)]
            (-> content
                (assoc-in [index :params cx] (:x point))
                (assoc-in [index :params cy] (:y point)))))]
    (as-> content $
      (reduce change-content $ handlers)
      (remove-line-curves $))))

(defn make-curve-point
  "Changes the content to make the point a 'curve'. The handlers will be positioned
  in the same vector that results from te previous->next points but with fixed length."
  [content point]
  (let [content-next (d/enumerate (d/with-prev-next content))

        make-curve
        (fn [command previous]
          (if (= :line-to (:command command))
            (let [cur-point (command->point command)
                  pre-point (command->point previous)]
              (-> command
                  (assoc :command :curve-to)
                  (assoc :params (make-curve-params cur-point pre-point))))
            command))

        update-handler
        (fn [command prefix handler]
          (if (= :curve-to (:command command))
            (let [cx (d/prefix-keyword prefix :x)
                  cy (d/prefix-keyword prefix :y)]
              (-> command
                  (assoc-in [:params cx] (:x handler))
                  (assoc-in [:params cy] (:y handler))))
            command))

        calculate-vector
        (fn [point next prev]
          (let [base-vector (if (or (nil? next) (nil? prev) (= next prev))
                              (-> (gpt/to-vec point (or next prev))
                                  (gpt/normal-left))
                              (gpt/to-vec next prev))]
            (-> base-vector
                (gpt/unit)
                (gpt/multiply (gpt/point 100)))))

        redfn (fn [content [index [command prev next]]]
                (if (= point (command->point command))
                  (let [prev-point (if (= :move-to (:command command)) nil (command->point prev))
                        next-point (if (= :move-to (:command next)) nil (command->point next))
                        handler-vector (calculate-vector point next-point prev-point)
                        handler (gpt/add point handler-vector)
                        handler-opposite (gpt/add point (gpt/negate handler-vector))]
                    (-> content
                        (cd/update-when index make-curve prev)
                        (cd/update-when index update-handler :c2 handler)
                        (cd/update-when (inc index) make-curve command)
                        (cd/update-when (inc index) update-handler :c1 handler-opposite)))

                  content))]
    (as-> content $
      (reduce redfn $ content-next)
      (remove-line-curves $))))
