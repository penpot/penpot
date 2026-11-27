;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.drain
  "Sets in the background the pages that a lazy change of the tokens left
  out of date, so the readers don't have to set them every time.

  The pages are computed in a worker of their own (`app.worker.tokens-drain`),
  not the one that answers selection, hover and snap, one at a time; the
  main thread only applies the result, in chunks, and saves each page. A
  first pass asks the worker for the thumbnails that each page leaves out of
  date and clears them, so they are right before the pages are set. The
  drain is optional: a page it doesn't set is set when it is opened or read,
  so an error, a timeout or a dead worker only stop it.

  Each request carries the version of the tokens it is for, the resolved
  values: a request for the same version adds only the pages that are not
  waiting yet; a new version takes the pages of the run it follows that are
  not set yet, and the token names of both, and a page computed for an
  older one is discarded. A page is applied only when its shapes still hold
  the values the worker read; otherwise it is left for the editors."
  (:require
   [app.common.logging :as l]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.types.objects-map :as omap]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.leader :as leader]
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.fonts :as fonts]
   [app.util.activity :as activity]
   [app.util.timers :as tm]
   [app.util.worker :as uw]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(l/set-level! :info)

(def page-timeout-ms
  "Longest wait for the worker to compute a page."
  120000)

(def idle-terminate-ms
  "Time without pages to drain before the worker is stopped."
  30000)

(def chunk-size
  "Shapes set in each commit of a drained page, so the main thread paints
  between them."
  500)

;; --- Worker

(defonce ^:private worker* (atom nil))

(defn- on-worker-error
  [& args]
  (l/wrn :hint "drain worker error" :cause (last args)))

(defn- worker
  "The drain worker, started when it is first needed."
  []
  (or @worker*
      (reset! worker* (uw/init cf/worker-uri on-worker-error))))

(defn- terminate-worker
  []
  (when-let [w @worker*]
    (reset! worker* nil)
    (uw/terminate w)))

(defn ask-worker
  "Observable of the result of the drain worker for `message`, that fails
  after `page-timeout-ms`, or when the worker can't start."
  [message]
  (->> (rx/defer #(uw/ask! (worker) message))
       (rx/timeout page-timeout-ms)))

;; --- Applying a page

(defn- holds?
  "True when `shape` holds the value that the undo operation `op` sets back."
  [shape {:keys [type attr val values touched]}]
  (case type
    :set              (= val (get shape attr))
    :set-token-values (every? (fn [[attr value]] (= value (get-in shape [:token-values attr]))) values)
    :set-touched      (= touched (:touched shape))
    true))

(defn holds-read-values?
  "True when `page` still holds the values that `undo-changes`, the undo of
  a drained page, set back: nothing changed them since the worker read it."
  [page undo-changes]
  (every? (fn [{:keys [type id operations] :as change}]
            (case type
              :mod-obj  (let [shape (get (:objects page) id)]
                          (and (some? shape) (every? #(holds? shape %) operations)))
              :mod-page (every? (fn [[k v]] (= v (get page k))) (dissoc change :type :id))
              true))
          undo-changes))

(defn- chunks
  "The commits of a drained page: the `:mod-obj` changes of `chunk-size`
  shapes at a time with their undo, all the changes of a shape in the same
  one, and the other changes in the last one."
  [{:keys [redo-changes undo-changes]}]
  (let [mod-obj? #(= :mod-obj (:type %))
        redo-by  (group-by :id (filter mod-obj? redo-changes))
        undo-by  (group-by :id (filter mod-obj? undo-changes))
        parts    (->> (filter mod-obj? redo-changes)
                      (map :id)
                      (distinct)
                      (partition-all chunk-size))
        parts    (if (seq parts) (vec parts) [[]])
        others   [(remove mod-obj? redo-changes) (remove mod-obj? undo-changes)]]
    (map-indexed (fn [i ids]
                   (let [last? (= i (dec (count parts)))]
                     {:redo-changes (cond-> (into [] (mapcat redo-by) ids) last? (into (first others)))
                      :undo-changes (cond-> (into [] (mapcat undo-by) ids) last? (into (second others)))}))
                 parts)))

(defn- commit-chunk
  [{:keys [redo-changes undo-changes]}]
  (dch/commit-changes {:redo-changes redo-changes
                       :undo-changes undo-changes
                       :save-undo? false
                       :tags #{:token-propagation}
                       ;; Follows the tokens already committed
                       :ignore-save-lock true}))

;; --- Queue
;;
;; The drain of the state (`::drain`) is a run, `{:run-id :version
;; :token-names :thumbnails :queue :current :pass}`: the pages of
;; `:thumbnails` go first, to clear their stale thumbnails, and then the ones
;; of `:queue`, to set them. Every event of its loop carries the run id and
;; does nothing once the run is not the one of the state. A run stopped by
;; another change of the tokens leaves its pages not set yet in `::left`,
;; `{:file-id :token-names :page-ids}`, for the run that follows.

(declare next-page)

(defn- current-run?
  [state run-id]
  (= run-id (get-in state [::drain :run-id])))

(defn- merge-names
  "The token names of both, nil (any token) when either is nil."
  [names other]
  (when (and (some? names) (some? other))
    (into (vec names) (remove (set names)) other)))

(defn- merge-pages
  "`page-ids` followed by those of `other` that are not in it."
  [page-ids other]
  (into (vec page-ids) (remove (set page-ids)) other))

(defn- not-set
  "The pages of the run `drain` not set yet, the one in progress included,
  and the token names they wait for."
  [{:keys [token-names queue current pass]}]
  {:token-names token-names
   :page-ids    (cond-> (vec queue)
                  (and (= pass :values) (some? current)) (conj current))})

(defn- stop
  "Event that ends the run `run-id`; its pages not set yet are left for the
  next run, or forgotten with `terminate?`, which stops the worker too."
  [run-id terminate?]
  (let [stopped? (volatile! false)]
    (ptk/reify ::stop
      cljs.core/IDeref
      (-deref [_] run-id)

      ptk/UpdateEvent
      (update [_ state]
        (if (current-run? state run-id)
          (do (vreset! stopped? true)
              (cond-> (dissoc state ::drain)
                (not terminate?)
                (assoc ::left (assoc (not-set (::drain state))
                                     :file-id (:current-file-id state)))))
          state))

      ptk/EffectEvent
      (effect [_ _ _]
        (when (and terminate? @stopped?)
          (terminate-worker))))))

(defn- terminate-when-idle
  "Event that stops the worker when no drain runs."
  []
  (ptk/reify ::terminate-when-idle
    ptk/EffectEvent
    (effect [_ state _]
      (when (nil? (::drain state))
        (terminate-worker)))))

(defn- tokens-commit?
  [event]
  (and (dch/commit? event)
       (pv/tokens-change? (:redo-changes (deref event)))))

(defn- finalize?
  [event]
  (ptk/type? ::dw/finalize-workspace event))

(defn- stopper
  "Observable of the event that ends the run `run-id` when the tokens change
  again (a new drain follows the propagation of the change) or the
  workspace closes, while the run lasts."
  [stream run-id]
  (->> stream
       (rx/filter #(or (tokens-commit? %) (finalize? %)))
       (rx/take 1)
       (rx/map #(stop run-id (finalize? %)))
       (rx/take-until (rx/filter #(and (ptk/type? ::run-ended %) (= run-id (deref %))) stream))))

(defn- run-ended
  [run-id]
  (ptk/reify ::run-ended
    cljs.core/IDeref
    (-deref [_] run-id)

    ptk/UpdateEvent
    (update [_ state]
      (if (current-run? state run-id)
        (dissoc state ::drain)
        state))

    ptk/WatchEvent
    (watch [_ _ stream]
      ;; The worker stops when no drain comes for a while
      (->> (rx/timer idle-terminate-ms)
           (rx/take-until (rx/filter (ptk/type? ::start) stream))
           (rx/map (fn [_] (terminate-when-idle)))))))

(defn- page-open?
  "True when an editor has `page-id` open, this session or another one: the
  values of an open page are set by the editors on it
  (`app.main.data.workspace.tokens.correction`)."
  [state page-id]
  (or (= page-id (:current-page-id state))
      (leader/page-editor? (:workspace-presence state) page-id)))

(defn- apply-chunks
  "Event that applies the first of `chunks`, what is left of `result`, the
  drain of `page-id` for the tokens `version`, and then the next ones. Each
  one goes only while the run and the version are the ones to drain, no
  editor has the page open and its shapes still hold what the worker read:
  the objects are still `sent-objects`, or the values match. The page is
  saved and its thumbnails cleared once some chunk is `applied?`."
  [run-id page-id version sent-objects result chunks applied?]
  (ptk/reify ::apply-chunks
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)
            page    (dsh/lookup-page state page-id)
            chunk   (first chunks)
            run?    (current-run? state run-id)
            done    (rx/concat
                     (if applied?
                       (rx/concat
                        (dwt/clear-stale-thumbnails file-id page-id result)
                        ;; Each page is saved as soon as it is set
                        (rx/of (ptk/data-event ::page-applied {:page-id page-id})
                               ::dps/force-persist))
                       (rx/empty))
                     (if run? (rx/of (next-page run-id)) (rx/empty)))]
        (cond
          (or (not run?)
              (nil? chunk)
              (nil? page)
              (not= version (get-in state [::drain :version]))
              (page-open? state page-id))
          done

          ;; The same objects: nothing changed the page since it was sent
          (not (or (identical? sent-objects (:objects page))
                   (holds-read-values? page (:undo-changes chunk))))
          (do (l/inf :hint "drained page changed meanwhile, left for the editors" :page-id (str page-id))
              done)

          :else
          (rx/concat
           (rx/of (commit-chunk chunk))
           (rx/ignore (tm/next-task))
           (rx/of (apply-chunks run-id page-id version nil result (rest chunks) true))))))))

(defn- page-drained
  "Event that applies `result`, the drain of `page-id` for the tokens
  `version` from its objects `sent-objects`, and goes on with the next page
  of the run."
  [run-id page-id version sent-objects result]
  (ptk/reify ::page-drained
    ptk/WatchEvent
    (watch [_ state _]
      (cond
        (not (current-run? state run-id))
        (rx/empty)

        (nil? result)
        (rx/of (next-page run-id))

        :else
        (rx/of (apply-chunks run-id page-id version sent-objects result (chunks result) false))))))

(defn- thumbnails-found
  "Event that clears `result`, the stale thumbnails of `page-id` for the
  tokens `version`, and goes on with the next page of the run."
  [run-id page-id version result]
  (ptk/reify ::thumbnails-found
    ptk/WatchEvent
    (watch [_ state _]
      (cond
        (not (current-run? state run-id))
        (rx/empty)

        (= version (get-in state [::drain :version]))
        (rx/concat
         (dwt/clear-stale-thumbnails (:current-file-id state) page-id result)
         (rx/of (next-page run-id)))

        :else
        (rx/of (next-page run-id))))))

(defn- page-message
  "The request `cmd` for `page`, with its shapes as they are encoded."
  [cmd page version token-names]
  (cond-> {:cmd cmd
           :page page
           :values version
           :token-names token-names}
    (= cmd :tokens/drain-page) (assoc :custom-fonts (fonts/custom-fonts))))

(defn- encode-changed
  "Observable that completes once the shapes of `objects` changed in memory
  are encoded again, `chunk-size` of them per task: the message to the
  worker would otherwise encode them all at once. The others travel as they
  are encoded."
  [objects]
  (if (omap/objects-map? objects)
    (->> (rx/from (partition-all chunk-size (omap/changed-ids objects)))
         (rx/mapcat (fn [ids]
                      (rx/concat
                       (rx/defer #(do (omap/compact-ids objects ids)
                                      (rx/empty)))
                       (tm/next-task))))
         (rx/ignore))
    (rx/empty)))

(defn- next-page
  "Event that takes the first page waiting in the run `run-id`, to clear its
  thumbnails or to set it, or ends the run when there is none."
  [run-id]
  (ptk/reify ::next-page
    ptk/UpdateEvent
    (update [_ state]
      (if (current-run? state run-id)
        (update state ::drain
                (fn [{:keys [thumbnails queue] :as drain}]
                  (if (seq thumbnails)
                    (assoc drain :pass :thumbnails :current (first thumbnails) :thumbnails (vec (rest thumbnails)))
                    (assoc drain :pass :values :current (first queue) :queue (vec (rest queue))))))
        state))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [current pass version token-names]} (::drain state)
            page (some->> current (dsh/lookup-page state))]
        (cond
          (not (current-run? state run-id))
          (rx/empty)

          (nil? current)
          (rx/of (run-ended run-id))

          ;; The worker tells the pages with nothing to set: looking for
          ;; them here would decode every shape on the main thread
          (or (nil? page) (page-open? state current))
          (rx/of (next-page run-id))

          :else
          (->> (rx/concat
                (encode-changed (:objects page))
                ;; Asked once the shapes are encoded
                (if (= pass :thumbnails)
                  (->> (rx/defer #(ask-worker (page-message :tokens/stale-thumbnails page version token-names)))
                       (rx/map #(thumbnails-found run-id current version %)))
                  (->> (rx/defer #(ask-worker (page-message :tokens/drain-page page version token-names)))
                       (rx/mapcat (fn [result]
                                    (rx/concat (rx/ignore (activity/pause))
                                               (rx/of (page-drained run-id current version (:objects page) result))))))))
               (rx/catch (fn [cause]
                           (l/wrn :hint "drain stopped" :page-id (str current) :cause cause)
                           (rx/of (stop run-id true))))
               ;; A run stopped leaves its page
               (rx/take-until (rx/filter #(and (ptk/type? ::stop %) (= run-id (deref %))) stream))))))))

(defn start
  "Event that drains the pages `page-ids`, left out of date with
  `resolved-tokens` by a lazy change of the tokens `changed` (a map of
  token name to token, nil for any token)."
  [resolved-tokens changed page-ids]
  (let [version     (cltp/resolved-values resolved-tokens)
        token-names (when (some? changed) (vec (keys changed)))
        new-run-id  (random-uuid)]
    (ptk/reify ::start
      ptk/UpdateEvent
      (update [_ state]
        (let [{:keys [queue current] :as drain} (::drain state)
              left  (::left state)
              ;; The pages left by a run stopped in this file
              left  (when (= (:file-id left) (:current-file-id state)) left)
              state (dissoc state ::left)]
          (cond
            (nil? drain)
            (let [pages (merge-pages page-ids (:page-ids left))]
              (assoc state ::drain {:run-id new-run-id
                                    :version version
                                    :token-names (if (some? left)
                                                   (merge-names token-names (:token-names left))
                                                   token-names)
                                    :thumbnails pages
                                    :queue pages}))

            (= version (:version drain))
            ;; The same tokens: only the pages that don't wait yet
            (let [waiting (cond-> (set queue) (some? current) (conj current))
                  added   (into [] (remove waiting) page-ids)]
              (update state ::drain
                      #(-> %
                           (update :thumbnails (fn [pages] (into (vec pages) added)))
                           (update :queue (fn [queue] (into (vec queue) added)))
                           (update :token-names merge-names token-names))))

            :else
            ;; Newer tokens: their pages and the ones not set yet, the page
            ;; in progress included, as its result is discarded when it comes
            (let [{left-names :token-names left-pages :page-ids} (not-set drain)
                  pages (merge-pages page-ids left-pages)]
              (update state ::drain assoc
                      :version version
                      :token-names (merge-names token-names left-names)
                      :thumbnails pages
                      :queue pages)))))

      ptk/WatchEvent
      (watch [_ state stream]
        ;; A new run starts its loop; a run in progress takes the new pages
        (when (current-run? state new-run-id)
          (rx/merge
           (stopper stream new-run-id)
           (rx/of (next-page new-run-id))))))))
