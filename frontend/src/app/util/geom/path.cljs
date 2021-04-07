;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.geom.path
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gshp]
   [app.util.a2c :refer [a2c]]
   [app.util.geom.path-impl-simplify :as impl-simplify]
   [app.util.svg :as usvg]
   [cuerdas.core :as str]))

(defn calculate-opposite-handler
  "Given a point and its handler, gives the symetric handler"
  [point handler]
  (let [handler-vector (gpt/to-vec point handler)]
    (gpt/add point (gpt/negate handler-vector))))

(defn simplify
  "Simplifies a drawing done with the pen tool"
  ([points]
   (simplify points 0.1))
  ([points tolerance]
   (let [points (into-array points)]
     (into [] (impl-simplify/simplify points tolerance true)))))

;;
(def commands-regex #"(?i)[mzlhvcsqta][^mzlhvcsqta]*")

;; Matches numbers for path values allows values like... -.01, 10, +12.22
;; 0 and 1 are special because can refer to flags
(def num-regex #"[+-]?(\d+(\.\d+)?|\.\d+)(e[+-]?\d+)?")

(def flag-regex #"[01]")

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
        (let [value (-> match first usvg/fix-dot-number d/read-string)
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

(defn command->point [command]
  (when-not (nil? command)
    (let [{{:keys [x y]} :params} command]
      (gpt/point x y))))

(defn command->param-list [command]
  (let [params (:params command)]
    (case (:command command)
      (:move-to :line-to :smooth-quadratic-bezier-curve-to)
      (str (:x params) ","
           (:y params))

      :close-path
      ""

      (:line-to-horizontal :line-to-vertical)
      (str (:value params))

      :curve-to
      (str (:c1x params) ","
           (:c1y params) ","
           (:c2x params) ","
           (:c2y params) ","
           (:x params) ","
           (:y params))

      (:smooth-curve-to :quadratic-bezier-curve-to)
      (str (:cx params) ","
           (:cy params) ","
           (:x params) ","
           (:y params))

      :elliptical-arc
      (str (:rx params) ","
           (:ry params) ","
           (:x-axis-rotation params) ","
           (:large-arc-flag params) ","
           (:sweep-flag params) ","
           (:x params) ","
           (:y params)))))

;; Path specification
;; https://www.w3.org/TR/SVG11/paths.html
(defmulti parse-command (comp str/upper first))

(defmethod parse-command "M" [cmd]
  (let [relative (str/starts-with? cmd "m")
        param-list (extract-params cmd [[:x :number]
                                        [:y :number]])]

    (d/concat [{:command :move-to
                :relative relative
                :params (first param-list)}]

              (for [params (rest param-list)]
                {:command :line-to
                 :relative relative
                 :params params}))))

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
        param-list (extract-params cmd [[:cx :number]
                                        [:cy :number]
                                        [:x  :number]
                                        [:y  :number]])]
    (for [params param-list]
      {:command :smooth-curve-to
       :relative relative
       :params params})))

(defmethod parse-command "Q" [cmd]
  (let [relative (str/starts-with? cmd "q")
        param-list (extract-params cmd [[:cx :number]
                                        [:cy :number]
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
    (str command-str param-list)))

(defn cmd-pos [prev-pos {:keys [relative params]}]
  (let [{:keys [x y] :or {x (:x prev-pos) y (:y prev-pos)}} params]
    (if relative
      (-> prev-pos (update :x + x) (update :y + y))
      (gpt/point x y))))

(defn arc->beziers [from-p command]
  (let [to-command
        (fn [[_ _ c1x c1y c2x c2y x y]]
          {:command :curve-to
           :relative (:relative command)
           :params {:c1x c1x :c1y c1y
                    :c2x c2x :c2y c2y
                    :x   x   :y   y}})

        {from-x :x from-y :y} from-p
        {:keys [rx ry x-axis-rotation large-arc-flag sweep-flag x y]} (:params command)
        result (a2c from-x from-y x y large-arc-flag sweep-flag rx ry x-axis-rotation)]

    (mapv to-command result)))

(defn smooth->curve
  [{:keys [params]} pos handler]
  (let [{c1x :x c1y :y} (calculate-opposite-handler pos handler)]
    {:c1x c1x
     :c1y c1y
     :c2x (:cx params)
     :c2y (:cy params)}))

(defn quadratic->curve
  [sp ep cp]
  (let [cp1 (-> (gpt/to-vec sp cp)
                (gpt/scale (/ 2 3))
                (gpt/add sp))

        cp2 (-> (gpt/to-vec ep cp)
                (gpt/scale (/ 2 3))
                (gpt/add ep))]

    {:c1x (:x cp1)
     :c1y (:y cp1)
     :c2x (:x cp2)
     :c2y (:y cp2)}))

(defn simplify-commands
  "Removes some commands and convert relative to absolute coordinates"
  [commands]
  (let [simplify-command
        ;; prev-pos   : previous position for the current path. Necesary for relative commands
        ;; prev-start : previous move-to necesary for Z commands
        ;; prev-cc    : previous command control point for cubic beziers
        ;; prev-qc    : previous command control point for quadratic curves
        (fn [[result prev-pos prev-start prev-cc prev-qc] [command prev]]
          (let [command (assoc command :prev-pos prev-pos)

                command
                (cond-> command
                  (:relative command)
                  (-> (assoc :relative false)
                      (d/update-in-when [:params :c1x] + (:x prev-pos))
                      (d/update-in-when [:params :c1y] + (:y prev-pos))

                      (d/update-in-when [:params :c2x] + (:x prev-pos))
                      (d/update-in-when [:params :c2y] + (:y prev-pos))

                      (d/update-in-when [:params :cx] + (:x prev-pos))
                      (d/update-in-when [:params :cy] + (:y prev-pos))

                      (d/update-in-when [:params :x] + (:x prev-pos))
                      (d/update-in-when [:params :y] + (:y prev-pos))

                      (cond->
                          (= :line-to-horizontal (:command command))
                        (d/update-in-when [:params :value] + (:x prev-pos))

                        (= :line-to-vertical (:command command))
                        (d/update-in-when [:params :value] + (:y prev-pos)))))

                params (:params command)
                orig-command command

                command
                (cond-> command
                  (= :line-to-horizontal (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (assoc-in [:params :x] (:value params))
                      (assoc-in [:params :y] (:y prev-pos)))

                  (= :line-to-vertical (:command command))
                  (-> (assoc :command :line-to)
                      (update :params dissoc :value)
                      (assoc-in [:params :y] (:value params))
                      (assoc-in [:params :x] (:x prev-pos)))

                  (= :smooth-curve-to (:command command))
                  (-> (assoc :command :curve-to)
                      (update :params dissoc :cx :cy)
                      (update :params merge (smooth->curve command prev-pos prev-cc)))

                  (= :quadratic-bezier-curve-to (:command command))
                  (-> (assoc :command :curve-to)
                      (update :params dissoc :cx :cy)
                      (update :params merge (quadratic->curve prev-pos (gpt/point params) (gpt/point (:cx params) (:cy params)))))

                  (= :smooth-quadratic-bezier-curve-to (:command command))
                  (-> (assoc :command :curve-to)
                      (update :params merge (quadratic->curve prev-pos (gpt/point params) (calculate-opposite-handler prev-pos prev-qc)))))

                result (if (= :elliptical-arc (:command command))
                         (d/concat result (arc->beziers prev-pos command))
                         (conj result command))

                next-cc (case (:command orig-command)
                          :smooth-curve-to
                          (gpt/point (get-in orig-command [:params :cx]) (get-in orig-command [:params :cy]))

                          :curve-to
                          (gpt/point (get-in orig-command [:params :c2x]) (get-in orig-command [:params :c2y]))

                          (:line-to-horizontal :line-to-vertical)
                          (gpt/point (get-in command [:params :x]) (get-in command [:params :y]))

                          (gpt/point (get-in orig-command [:params :x]) (get-in orig-command [:params :y])))

                next-qc (case (:command orig-command)
                          :quadratic-bezier-curve-to
                          (gpt/point (get-in orig-command [:params :cx]) (get-in orig-command [:params :cy]))

                          :smooth-quadratic-bezier-curve-to
                          (calculate-opposite-handler prev-pos prev-qc)

                          (gpt/point (get-in orig-command [:params :x]) (get-in orig-command [:params :y])))

                next-pos (if (= :close-path (:command command))
                           prev-start
                           (cmd-pos prev-pos command))

                next-start (if (= :move-to (:command command)) next-pos prev-start)]

            [result next-pos next-start next-cc next-qc]))

        start (first commands)
        start-pos (gpt/point (:params start))]

    (->> (map vector (rest commands) commands)
                    (reduce simplify-command [[start] start-pos start-pos start-pos start-pos])
                    (first))))

(defn path->content [path-str]
  (let [clean-path-str
        (-> path-str
            (str/trim)
            ;; Change "commas" for spaces
            (str/replace #"," " ")
            ;; Remove all consecutive spaces
            (str/replace #"\s+" " "))
        commands (re-seq commands-regex clean-path-str)]
    (-> (mapcat parse-command commands)
        (simplify-commands))))

(defn content->path [content]
  (->> content
       (mapv command->string)
       (str/join "")))

(defn make-line-to [to]
  {:command :line-to
   :relative false
   :params {:x (:x to)
            :y (:y to)}})

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

(defn make-curve-to [to h1 h2]
  {:command :curve-to
   :relative false
   :params (make-curve-params to h1 h2)})

(defn split-line-to [from-p cmd val]
  (let [to-p (command->point cmd)
        sp (gpt/line-val from-p to-p val)]
    [(make-line-to sp) cmd]))

(defn split-curve-to [from-p cmd val]
  (let [params (:params cmd)
        end (gpt/point (:x params) (:y params))
        h1 (gpt/point (:c1x params) (:c1y params))
        h2 (gpt/point (:c2x params) (:c2y params))
        [[_ to1 h11 h21]
         [_ to2 h12 h22]] (gshp/curve-split from-p end h1 h2 val)]
    [(make-curve-to to1 h11 h21)
     (make-curve-to to2 h12 h22)]))

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
       (d/mapm #(mapv second %2))))

(defn point-indices
  [content point]
  (->> (d/enumerate content)
       (filter (fn [[_ cmd]] (= point (command->point cmd))))
       (mapv (fn [[index _]] index))))

(defn handler-indices
  [content point]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-cmd pre-cmd]]]
                 (if (and (some? pre-cmd) (= :curve-to (:command cur-cmd)))
                   (let [cur-pos (command->point cur-cmd)
                         pre-pos (command->point pre-cmd)]
                     (cond-> []
                       (= pre-pos point) (conj [index :c1])
                       (= cur-pos point) (conj [index :c2])))
                   [])))))

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
                        (d/update-when index make-curve prev)
                        (d/update-when index update-handler :c2 handler)
                        (d/update-when (inc index) make-curve command)
                        (d/update-when (inc index) update-handler :c1 handler-opposite)))

                  content))]
    (as-> content $
      (reduce redfn $ content-next)
      (remove-line-curves $))))

(defn get-segments
  "Given a content and a set of points return all the segments in the path
  that uses the points"
  [content points]
  (let [point-set (set points)]

    (loop [segments []
           prev-point nil
           start-point nil
           cur-cmd (first content)
           content (rest content)]

      (let [;; Close-path makes a segment from the last point to the initial path point
            cur-point (if (= :close-path (:command cur-cmd))
                        start-point
                        (command->point cur-cmd))

            ;; If there is a move-to we don't have a segment
            prev-point (if (= :move-to (:command cur-cmd))
                         nil
                         prev-point)

            ;; We update the start point
            start-point (if (= :move-to (:command cur-cmd))
                          cur-point
                          start-point)

            is-segment? (and (some? prev-point)
                             (contains? point-set prev-point)
                             (contains? point-set cur-point))

            segments (cond-> segments
                       is-segment?
                       (conj [prev-point cur-point cur-cmd]))]

        (if (some? cur-cmd)
          (recur segments
                 cur-point
                 start-point
                 (first content)
                 (rest content))

          segments)))))

(defn split-segments
  "Given a content creates splits commands between points with new segments"
  [content points value]
  (let [split-command
        (fn [[start end cmd]]
          (case (:command cmd)
            :line-to [cmd (split-line-to start cmd value)]
            :curve-to [cmd (split-curve-to start cmd value)]
            :close-path [cmd [(make-line-to (gpt/line-val start end value)) cmd]]
            nil))

        cmd-changes
        (->> (get-segments content points)
             (into {} (comp (map split-command)
                            (filter (comp not nil?)))))

        process-segments
        (fn [command]
          (if (contains? cmd-changes command)
            (get cmd-changes command)
            [command]))]

    (into [] (mapcat process-segments) content)))

(defn remove-nodes
  "Removes from content the points given. Will try to reconstruct the paths
  to keep everything consistent"
  [content points]

  (let [content (d/with-prev content)]

    (loop [result []
           last-handler nil
           [cur-cmd prev-cmd] (first content)
           content (rest content)]

      (if (nil? cur-cmd)
        ;; The result with be an array of arrays were every entry is a subpath
        (->> result
             ;; remove empty and only 1 node subpaths
             (filter #(> (count %) 1))
             ;; flatten array-of-arrays plain array
             (flatten)
             (into []))

        (let [move? (= :move-to (:command cur-cmd))
              curve? (= :curve-to (:command cur-cmd))

              ;; When the old command was a move we start a subpath
              result (if move? (conj result []) result)

              subpath (peek result)

              point (command->point cur-cmd)
              
              old-prev-point (command->point prev-cmd)
              new-prev-point (command->point (peek subpath))

              remove? (contains? points point)

              
              ;; We store the first handler for the first curve to be removed to
              ;; use it for the first handler of the regenerated path
              cur-handler (cond
                            (and (not last-handler) remove? curve?)
                            (select-keys (:params cur-cmd) [:c1x :c1y])

                            (not remove?)
                            nil

                            :else
                            last-handler)

              cur-cmd (cond-> cur-cmd
                        ;; If we're starting a subpath and it's not a move make it a move
                        (and (not move?) (empty? subpath))
                        (assoc :command :move-to
                               :params (select-keys (:params cur-cmd) [:x :y]))

                        ;; If have a curve the first handler will be relative to the previous
                        ;; point. We change the handler to the new previous point
                        (and curve? (not (empty? subpath)) (not= old-prev-point new-prev-point))
                        (update :params merge last-handler))

              head-idx (dec (count result))

              result (cond-> result
                       (not remove?)
                       (update head-idx conj cur-cmd))]
          (recur result
                 cur-handler
                 (first content)
                 (rest content)))))))

