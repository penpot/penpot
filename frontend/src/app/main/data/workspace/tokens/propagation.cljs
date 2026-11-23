;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.propagation
  "Sets the shapes to the values of their tokens after the tokens change.

  Only the token attributes that are out of date are set: the ones whose
  shape holds another value than its token resolves to, the stale ones and
  the unassigned ones (§ `app.common.types.token-values`)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.persistence.lock :as plk]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.drain :as drain]
   [app.main.data.workspace.tokens.lookup :as lookup]
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.undo :as dwu]
   [app.main.fonts :as fonts]
   [app.util.activity :as activity]
   [app.util.timers :as tm]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(l/set-level! :warn)

;; Targets ---------------------------------------------------------------------

(defn- token-names
  "Names of the `changed` tokens, nil for any token."
  [changed]
  (when (some? changed) (vec (keys changed))))

(defn- candidate-ids
  "Ids of the shapes of `objects` that may be out of date: the ones that
  apply some of the `changed` tokens, or every shape with tokens when
  `changed` is nil."
  [objects changed]
  (pv/candidate-ids objects (token-names changed)))

(defn- main-ids
  "Ids of the main components on `page-id`, from the components of `fdata`,
  without reading the other shapes of the page."
  [fdata page-id objects]
  (into []
        (comp (remove :deleted)
              (filter #(= page-id (:main-instance-page %)))
              (map :main-instance-id)
              (filter #(contains? objects %)))
        (vals (:components fdata))))

(defn- users-by-root
  "Map of each shape of `root-ids` to the ids of the shapes under it, or
  itself, that apply the `changed` tokens, found without decoding the
  shapes that are still encoded: on the main thread, unlike
  `pv/page-stale-thumbnails` in the drain worker."
  [objects root-ids changed]
  (let [root-ids (set root-ids)
        found    (volatile! {})
        roots-of (fn roots-of [id]
                   (if (contains? @found id)
                     (get @found id)
                     (let [parent-id (when-not (or (nil? id) (= id uuid/zero))
                                       (lookup/parent-id objects id))
                           roots     (cond-> (if (and (some? parent-id) (not= parent-id id))
                                               (roots-of parent-id)
                                               [])
                                       (contains? root-ids id) (conj id))]
                       (vswap! found assoc id roots)
                       roots)))]
    (reduce (fn [users id]
              (reduce #(update %1 %2 (fnil conj []) id) users (roots-of id)))
            {}
            (candidate-ids objects changed))))

(defn- thumbnail-frame-ids
  "Ids of the frames of `page-id` with a stored frame thumbnail."
  [state file-id page-id]
  (let [prefix (dm/str file-id "/" page-id "/")]
    (into []
          (comp (filter #(str/starts-with? % prefix))
                (map thc/parse-object-id)
                (filter #(= "frame" (:tag %)))
                (keep :frame-id))
          (keys (:thumbnails state)))))

(defn- stale-thumbnails
  "Events that clear the thumbnails of the frames `frame-ids` and of the
  main components `main-ids` of `page-id` that hold shapes applying the
  `changed` tokens."
  [file-id page-id objects frame-ids main-ids changed]
  (let [users  (users-by-root objects (concat frame-ids main-ids) changed)
        stale? #(contains? users %)]
    (dwt/clear-stale-thumbnails file-id page-id {:frame-ids (filter stale? frame-ids)
                                                 :main-ids  (filter stale? main-ids)})))

;; Propagation -----------------------------------------------------------------

(defn- propagate-page
  "Events that set the out of date shapes of a scope (`plan-scopes`) to
  `resolved-tokens`, in one commit tagged with `tags`. With
  `:thumbnails-only?` they clear the thumbnails it leaves out of date
  instead. The commit only follows tokens already committed, so it goes
  through while saves fail."
  [it state resolved-tokens changed
   {:keys [page-id root-ids save-resolved? thumbnails-only? main-ids frame-ids background?
           undo-group]
    scope-targets :targets} tags]
  (let [file-id  (get state :current-file-id)
        page     (dsh/lookup-page state file-id page-id)
        objects  (:objects page)
        other?   (not= page-id (get state :current-page-id))
        saved    (:tokens-resolved (dsh/lookup-file-data state file-id))

        targets
        (cond
          (some? scope-targets)
          scope-targets

          thumbnails-only?
          {}

          (some? root-ids)
          (cltp/out-of-date-targets objects resolved-tokens
                                    :ids (into [] (comp (mapcat #(cfh/get-children-ids-with-self objects %))
                                                        (distinct))
                                               root-ids)
                                    :unassigned? true)

          :else
          (cltp/out-of-date-targets objects resolved-tokens
                                    :ids (candidate-ids objects changed)
                                    :unassigned? true))

        changes
        (-> (pcb/empty-changes it)
            (pv/page-propagation page resolved-tokens targets
                                 {:fonts @fonts/fontsdb
                                  :geometry (dwta/geometry-mode state page-id)
                                  :reset-position-data? other?})
            ;; The readers set their shapes with the values saved in the file
            (cond-> save-resolved?
              (pcb/set-tokens-resolved saved (cltp/values-to-save resolved-tokens saved)))
            (cond-> (seq tags) (update :tags (fnil into #{}) tags))
            ;; Work in the background is not something to undo
            (cond-> background? (pcb/set-save-undo? false))
            (cond-> (and (some? undo-group) (not background?)) (assoc :undo-group undo-group)))]

    (rx/concat
     (dwta/commit-token-values state changes page-id objects targets true)
     (dwt/clear-stale-thumbnails file-id page-id (pv/stale-thumbnail-ids objects (pv/changed-shape-ids changes)))
     (if thumbnails-only?
       (stale-thumbnails file-id page-id objects frame-ids main-ids changed)
       (rx/empty)))))

(defn- page-affected?
  "True when `page` may have something to set for the `changed` tokens."
  [page changed]
  (pv/page-affected? page (token-names changed)))

(defn- page-scopes
  "Scopes that cover the pages of the file that may have something to
  update for the `changed` tokens, the current page first and always.
  With `lazy?` the other pages only clear their out of date thumbnails;
  with `thumbnails-only?` the current page too."
  [state changed lazy? thumbnails-only?]
  (let [fdata           (dsh/lookup-file-data state)
        file-id         (get state :current-file-id)
        current-page-id (get state :current-page-id)]
    (for [page-id (cons current-page-id
                        (remove #(= % current-page-id) (:pages fdata)))
          :let    [current? (= page-id current-page-id)
                   page     (dsh/get-page fdata page-id)
                   thumbs?  (and lazy? (or thumbnails-only? (not current?)))
                   main-ids (when thumbs? (main-ids fdata page-id (:objects page)))
                   frames   (when thumbs? (thumbnail-frame-ids state file-id page-id))]
          :when   (or current?
                      (if thumbs?
                        (or (seq main-ids) (seq frames) (some? (:background-token page)))
                        (page-affected? page changed)))]
      (cond-> {:page-id page-id}
        thumbs? (assoc :thumbnails-only? true :main-ids main-ids :frame-ids frames)))))

(defn- page-done
  "Event emitted when a scope has been set."
  [scope-id]
  (ptk/data-event ::page-done {:scope-id scope-id}))

(defn- page-done?
  [scope-id event]
  (and (ptk/type? ::page-done event)
       (= scope-id (:scope-id (deref event)))))

(defn- wait-for-user
  "Observable that emits once the user makes a pause, or at once when a
  newer change of the tokens waits for this one."
  [state stream]
  (if (> (count (::running state)) 1)
    (rx/of nil)
    (->> (rx/merge (activity/pause)
                   (rx/filter (ptk/type? ::propagate-workspace-tokens) stream))
         (rx/take 1))))

(defn- propagate-scope
  [resolved-tokens changed scope tags scope-id]
  (ptk/reify ::propagate-scope
    cljs.core/IDeref
    (-deref [_] scope-id)

    ptk/WatchEvent
    (watch [it state stream]
      ;; A failure still ends its work, so the dialog closes
      (->> (rx/defer
            (fn []
              (let [whole?  (and (nil? (:targets scope))
                                 (not (:thumbnails-only? scope))
                                 (nil? (:root-ids scope)))
                    objects (when whole? (:objects (dsh/lookup-page state (:page-id scope))))
                    targets (when whole?
                              (cltp/out-of-date-targets objects resolved-tokens
                                                        :ids (candidate-ids objects changed)
                                                        :unassigned? true))]
                (cond
                  ;; The work in the background waits for a pause of the user
                  (and (:background? scope)
                       (not (::waited? scope)))
                  (->> (wait-for-user state stream)
                       (rx/map #(propagate-scope resolved-tokens changed (assoc scope ::waited? true)
                                                 tags scope-id)))

                  (and whole? (dwtpr/long-work? objects targets))
                  ;; The dialog that blocks the workspace is painted before the work
                  (rx/concat
                   (rx/of (dwtpr/show))
                   (->> (rx/of (propagate-scope resolved-tokens changed (assoc scope :targets targets) tags scope-id))
                        (rx/delay dwtpr/paint-delay-ms)))

                  :else
                  (rx/concat
                   (propagate-page it state resolved-tokens changed
                                   (cond-> scope whole? (assoc :targets targets))
                                   tags)
                   (rx/of (dwtpr/work-done (:units scope 0))
                          (page-done scope-id)))))))
           (rx/catch #(rx/concat (rx/of (dwtpr/work-done (:units scope 0))
                                        (page-done scope-id))
                                 (rx/throw %)))))))

(defn- run-scopes
  "Observable that propagates `resolved-tokens` to each scope of `scopes`,
  one after another, letting the browser paint and handle input between
  them. Each scope reads the state left by the previous one."
  [stream resolved-tokens changed scopes tags]
  (if (empty? scopes)
    (rx/empty)
    (let [scope-id (uuid/next)]
      (rx/concat
       (rx/merge
        (->> stream
             (rx/filter (partial page-done? scope-id))
             (rx/take 1)
             (rx/ignore))
        (rx/of (propagate-scope resolved-tokens changed (first scopes) tags scope-id)))
       (rx/ignore (tm/next-task))
       (rx/defer #(run-scopes stream resolved-tokens changed (rest scopes) tags))))))

(defn changed-tokens
  "Entries of `resolved-tokens` whose resolved value differs from the one
  in `previous-tokens`."
  [resolved-tokens previous-tokens]
  (into {}
        (remove (fn [[token-name token]]
                  (= (:resolved-value token)
                     (dm/get-in previous-tokens [token-name :resolved-value]))))
        resolved-tokens))

(defn running-propagations
  "Ids of the propagations started and not finished yet."
  [state]
  (get state ::running))

(defn- propagation-finished
  [run-id]
  (ptk/reify ::propagation-finished
    cljs.core/IDeref
    (-deref [_] run-id)

    ptk/UpdateEvent
    (update [_ state]
      (update state ::running disj run-id))))

(defn- drain?
  "True when a lazy change of the tokens sets the other pages in the
  background."
  []
  (contains? cf/flags :lazy-token-propagation-drain))

(defn- plan-scopes
  "The scopes that set the `changed` tokens in `state` (see `run-scopes`),
  each with the `:units` of work it weighs, and their total `:units`;
  whether the pages after the first only clear their thumbnails in the
  background (`:background?`) and whether the drain sets the other pages
  (`:drain-pages?`)."
  [state changed {:keys [page-id root-ids thumbnails-only? undo-group]}]
  (let [lazy?        (dwta/lazy?)
        drain-pages? (and lazy? (drain?) (nil? root-ids) (not thumbnails-only?))
        scopes       (if (some? root-ids)
                       [{:page-id page-id :root-ids root-ids}]
                       (cond-> (vec (page-scopes state changed lazy? thumbnails-only?))
                         drain-pages? (subvec 0 1)
                         :always      (assoc-in [0 :save-resolved?] true)))
        scopes       (cond->> scopes
                       (some? undo-group) (mapv #(assoc % :undo-group undo-group)))
        background?  (boolean (and (next scopes) (every? :thumbnails-only? (rest scopes))))
        ;; The current page weighs as much as the other pages waited for
        scopes       (into [(assoc (first scopes) :units (if background? 1 (max 1 (dec (count scopes)))))]
                           (map #(if background? (assoc % :background? true :units 0) (assoc % :units 1)))
                           (rest scopes))]
    {:scopes       scopes
     :units        (reduce + (map :units scopes))
     :background?  background?
     :drain-pages? drain-pages?}))

(defn- resolve-changed
  "Observable of `[resolved-tokens changed]`: the active tokens of `state`
  resolved, and the ones that change, the tokens `token-names`, the ones
  whose value differs from `previous-tokens` resolved with
  `previous-base-font-size` (the current one when nil), or nil for any
  token."
  [state previous-tokens previous-base-font-size token-names]
  (->> (dwta/resolve-sets (dwta/active-tokens state) (dwta/base-font-size state))
       (rx/mapcat
        (fn [resolved-tokens]
          (cond
            (some? token-names)
            (rx/of [resolved-tokens (select-keys resolved-tokens token-names)])

            (some? previous-tokens)
            (->> (dwta/resolve-sets previous-tokens (d/nilv previous-base-font-size (dwta/base-font-size state)))
                 (rx/map #(vector resolved-tokens (changed-tokens resolved-tokens %))))

            :else
            (rx/of [resolved-tokens nil]))))))

(defn- propagate
  "Events that set the shapes to `resolved-tokens`, for the `changed` tokens,
  in one undo transaction: the current page first and then the other
  pages, or the drain."
  [state stream resolved-tokens changed {:keys [root-ids] :as opts}]
  (let [{:keys [scopes units background? drain-pages?]} (plan-scopes state changed opts)
        undo-id (js/Symbol)
        tpoint  (ct/tpoint-ms)
        ;; Covering every shape it updates the copies itself
        tags    (when (nil? root-ids) #{:token-propagation})]
    (l/inf :status "START" :hint "propagate-tokens"
           :tokens (count resolved-tokens) :pages (count scopes))
    (->> (rx/concat
          (rx/of (dwtpr/add-work units)
                 (dwu/start-undo-transaction undo-id :timeout false))
          (run-scopes stream resolved-tokens changed (take 1 scopes) tags)
          (if background?
            (rx/of (dwu/commit-undo-transaction undo-id))
            (rx/empty))
          ;; The texts of the current page are laid out before the other
          ;; pages are set; the change and the page are saved once the
          ;; dialog closes
          (if (or (next scopes) drain-pages?)
            (rx/of ::dw/layout-texts
                   (dwtpr/persist-when-closed))
            (rx/empty))
          (run-scopes stream resolved-tokens changed (rest scopes) tags)
          (if background?
            (rx/empty)
            (rx/of (dwu/commit-undo-transaction undo-id)))
          ;; The pages left out of date
          (if drain-pages?
            (let [current-page-id (:current-page-id state)]
              (rx/of (drain/start resolved-tokens changed
                                  (into [] (remove #(= % current-page-id))
                                        (:pages (dsh/lookup-file-data state))))))
            (rx/empty)))
         (rx/finalize
          #(l/inf :status "END" :hint "propagate-tokens" :elapsed (tpoint))))))

(defn- run-propagation
  "The work of a propagation, from the state when the previous one has
  finished."
  [run-id {:keys [previous-tokens previous-base-font-size token-names] :as opts}]
  (ptk/reify ::run-propagation
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/concat
       ;; A failure still ends the run, so the next ones go on
       (->> (rx/defer
             (fn []
               (if (some? (dwta/active-tokens state))
                 (->> (resolve-changed state previous-tokens previous-base-font-size token-names)
                      (rx/mapcat
                       (fn [[resolved-tokens changed]]
                         (if (and (some? changed) (empty? changed))
                           (rx/empty)
                           (propagate state stream resolved-tokens changed opts)))))
                 (rx/empty))))
            (rx/catch #(rx/concat
                        (rx/of (propagation-finished run-id))
                        (rx/throw %))))
       (rx/of (propagation-finished run-id))))))

(declare propagate-workspace-tokens)

(defn commit-token-changes
  "Commit `changes`, a change of the tokens or of the base font size, at
  once, and then set the shapes to the new values; one undo step reverts
  both. While saves fail the change is refused, and nothing follows it."
  [changes]
  (ptk/reify ::commit-token-changes
    ptk/WatchEvent
    (watch [_ state _]
      (when (and (seq (:redo-changes changes))
                 (not (plk/locked? state)))
        (let [undo-group (d/nilv (:undo-group changes) (uuid/next))]
          (rx/of (dch/commit-changes (-> changes
                                         (assoc :undo-group undo-group)
                                         ;; The propagation that follows sets the
                                         ;; current page, not its check after commits
                                         (update :tags (fnil conj #{}) :token-propagation)))
                 (propagate-workspace-tokens {:previous-tokens         (dwta/active-tokens state)
                                              :previous-base-font-size (dwta/base-font-size state)
                                              :undo-group              undo-group})))))))

(defn propagate-workspace-tokens
  "Set the shapes to the resolved values of the active tokens, where they
  are out of date. Options:
  - `:page-id` and `:root-ids` limit it to those shapes and their
    descendants. Without them it covers every page of the file, with a
    commit each.
  - `:previous-tokens`, the active token sets before a change, limits it to the
    shapes that apply the tokens whose resolved value changed.
  - `:previous-base-font-size`: the base font size `:previous-tokens`
    resolve with, the current one by default.
  - `:token-names` limits it to the shapes that apply those tokens.
  - `:undo-group`: the commits are undone with the other ones of that
    group.
  - `:thumbnails-only?`: set no shape, only clear the thumbnails left out
    of date, on every page.

  With the `:lazy-token-propagation` flag the pages other than the current
  one only clear their thumbnails left out of date.

  A propagation starts when the previous one has finished, so it sees the
  shapes it left."
  ([]
   (propagate-workspace-tokens nil))
  ([opts]
   (let [run-id        (uuid/next)
         previous-run* (volatile! nil)]
     (ptk/reify ::propagate-workspace-tokens
       ptk/UpdateEvent
       (update [_ state]
         (vreset! previous-run* (::last-run state))
         (-> state
             (assoc ::last-run run-id)
             (update ::running (fnil conj #{}) run-id)))

       ptk/WatchEvent
       (watch [_ state stream]
         (let [previous-run @previous-run*]
           (rx/concat
            (if (contains? (::running state) previous-run)
              (->> stream
                   (rx/filter #(and (ptk/type? ::propagation-finished %)
                                    (= previous-run (deref %))))
                   (rx/take 1)
                   (rx/ignore))
              (rx/empty))
            (rx/of (run-propagation run-id opts)))))))))
