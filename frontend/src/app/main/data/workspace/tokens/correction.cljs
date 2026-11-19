;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.correction
  "Keeps the shapes of the current page set to the values of their tokens.

  After the commits that may leave shapes out of date (remote commits,
  changes of the tokens, undo and redo, shapes from other files...) and when
  a page is opened, the out of date token attributes of the page are set
  again. Editors save the result; when other sessions have the page open
  only one of them does (`app.main.data.workspace.tokens.leader`). Users
  that can't edit, and version previews, set them in memory only."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logging :as l]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.types.token-values :as cttv]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.notifications :as-alias dwn]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.leader :as leader]
   [app.main.data.workspace.tokens.lookup :as lookup]
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(l/set-level! :warn)


;; --- Tokens

(declare editor?)

(defn- resolution-source
  "What the resolution of the tokens of `state` depends on."
  [state]
  {:tokens-lib     (dsh/lookup-tokens-lib state)
   :tokens-status  (dsh/lookup-tokens-status state)
   :base-font-size (dwta/base-font-size state)})

(defn current-resolution
  "The active tokens of `state` resolved by the last check, when the
  tokens, their status and the base font size haven't changed since;
  nil otherwise."
  [state]
  (let [source   (resolution-source state)
        snapshot (get state ::snapshot)]
    (when (and (some? snapshot)
               (identical? (:tokens-lib source) (:tokens-lib snapshot))
               (identical? (:tokens-status source) (:tokens-status snapshot))
               (= (:base-font-size source) (:base-font-size snapshot)))
      (:resolved snapshot))))

(defn- resolved-tokens
  "Observable of the active tokens of `state` resolved, the ones whose value
  changed since the last check (nil when unknown) and, when they were
  resolved again, what the resolution depends on; empty when the file has
  no tokens."
  [state]
  (if-let [resolved (current-resolution state)]
    (rx/of [resolved {}])
    (if-let [sets (dwta/active-tokens state)]
      (let [source (resolution-source state)]
        (->> (dwta/resolve-sets sets (:base-font-size source))
             (rx/map (fn [resolved]
                       [resolved
                        (when-let [previous (:resolved (get state ::snapshot))]
                          (dwtp/changed-tokens resolved previous))
                        source]))))
      (rx/empty))))

(declare waiting?)

(defn- set-snapshot
  "Keep the resolution of the tokens with what it depends on, `source`, and
  save it in the file for the readers when this session is an editor and
  doesn't wait for another one to do it (see `check-page` for `opts`)."
  [source resolved opts]
  (ptk/reify ::set-snapshot
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::snapshot (assoc source :resolved resolved)))

    ptk/WatchEvent
    (watch [it state _]
      (let [file-data (dsh/lookup-file-data state)
            saved     (:tokens-resolved file-data)
            values    (cltp/values-to-save resolved saved)]
        (when (and (editor? state)
                   (not (waiting? state opts))
                   (not (cttv/same-resolved-values? saved values)))
          (rx/of (dch/commit-changes
                  (-> (pcb/empty-changes it)
                      (pcb/set-save-undo? false)
                      (pcb/set-tokens-resolved saved values)
                      (assoc :tags #{:token-propagation})
                      ;; Follows the tokens already committed
                      (assoc :ignore-save-lock true)))))))))

;; --- Loop guard

(defn- guard-key
  [id attr resolved shape]
  [id attr (get-in resolved [(get-in shape [:applied-tokens attr]) :resolved-value])])

(defn- without-guarded
  "`targets` without the token attributes this session already set to the
  same value: two sessions that resolve a token differently answer each
  other once and stop."
  [targets objects resolved guard]
  (into {}
        (keep (fn [[id attrs]]
                (let [shape (get objects id)
                      attrs (into #{} (remove #(contains? guard (guard-key id % resolved shape))) attrs)]
                  (if (seq attrs)
                    [id attrs]
                    (do (l/dbg :hint "token value already set in this session" :shape-id (str id))
                        nil)))))
        targets))

(defn- add-to-guard
  [targets objects resolved]
  (ptk/reify ::add-to-guard
    ptk/UpdateEvent
    (update [_ state]
      (update state ::guard (fnil into #{})
              (for [[id attrs] targets
                    attr       attrs]
                (guard-key id attr resolved (get objects id)))))))

(defn- clear-guard
  "Forget the token attributes of the shapes `ids`, after a local edit
  changed them."
  [ids]
  (ptk/reify ::clear-guard
    ptk/UpdateEvent
    (update [_ state]
      (let [ids (set ids)]
        (update state ::guard #(into #{} (remove (fn [[id]] (contains? ids id))) %))))))

(defn- reset-guard
  "Forget every token attribute set, after a change of the tokens: the
  values to set are new."
  []
  (ptk/reify ::reset-guard
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state ::guard))))

;; --- Check

(defn editor?
  "True when this session can save changes to the file."
  [state]
  (and (true? (get-in state [:permissions :can-edit]))
       (nil? (get-in state [:workspace-global :preview-id]))))

(defn tokens-ready
  "Event emitted when the shapes of `page-id` hold the values of their
  tokens."
  [page-id]
  (ptk/data-event ::tokens-ready {:page-id page-id}))

(declare check-page)

(defn- page-opened
  "Record that `page-id` has been opened and the renderer loads its shapes."
  [page-id]
  (ptk/reify ::page-opened
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::opened-page-id page-id))))

(defn- shapes-loaded
  "Record that the renderer holds the shapes of `page-id`."
  [page-id]
  (ptk/reify ::shapes-loaded
    cljs.core/IDeref
    (-deref [_] page-id)

    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::shapes-page-id page-id))))

(defn- loading-shapes?
  "True when `page-id` has been opened and the renderer doesn't hold its
  shapes yet. The values of a page go through the renderer, so they wait
  for them."
  [state page-id]
  (and (features/active-feature? state "render-wasm/v1")
       (= page-id (::opened-page-id state))
       (not= page-id (::shapes-page-id state))))

(defn- apply-in-memory
  "Set the values without saving them, for sessions that can't save."
  [file-id changes]
  (dch/commit {:file-id file-id
               :redo-changes (:redo-changes changes)
               :undo-changes []
               :save-undo? false
               :source :in-memory}))

(defn- set-page-values
  [it state page-id objects resolved targets]
  (let [file-id (:current-file-id state)
        page    (dsh/lookup-page state file-id page-id)
        editor  (editor? state)
        changes (-> (pcb/empty-changes it page-id)
                    (pcb/with-page page)
                    (pcb/set-save-undo? false)
                    (cltp/generate-token-propagation objects resolved targets
                                                     {:fonts @fonts/fontsdb
                                                      :geometry (if editor
                                                                  (dwta/geometry-mode state page-id)
                                                                  :cljs)})
                    (assoc :tags #{:token-propagation}))]
    (cond
      (empty? (:redo-changes changes))
      (rx/empty)

      editor
      (rx/concat
       (dwta/commit-token-values state changes page-id objects targets true)
       (dwt/clear-stale-thumbnails file-id page-id (pv/stale-thumbnail-ids objects (keys targets))))

      :else
      (rx/of (apply-in-memory file-id changes)))))

(defn- queue-position
  "Position of this session in the queue of the editors that set the
  current page after a change of other sessions (`:remote?` in `opts`): 0
  goes first, and also when nobody waits for anybody."
  [state {:keys [remote? author-id]}]
  (if (and (editor? state) remote?)
    (let [presence (:workspace-presence state)
          page-id  (:current-page-id state)]
      (leader/position presence
                       (:session-id state)
                       page-id
                       (leader/author-first presence page-id author-id (dwta/lazy?))))
    0))

(defn- waiting?
  "True when another session sets the current page first."
  [state opts]
  (pos? (queue-position state opts)))

(defn- set-values
  "Set the token values `targets` of `page-id` from its shapes when it runs."
  [page-id resolved targets]
  (ptk/reify ::set-values
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (:objects (dsh/lookup-page state page-id))]
        (set-page-values it state page-id objects resolved targets)))))

(defn- set-out-of-date
  "Set the out of date token attributes of the page for `resolved` tokens,
  reading the shapes when it runs."
  [{:keys [ids opening? remote?] :as opts} resolved changed]
  (ptk/reify ::set-out-of-date
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            file-id  (:current-file-id state)
            objects  (:objects (dsh/lookup-page state file-id page-id))
            editor   (editor? state)
            ids      (if (or (nil? ids) (nil? changed))
                       (into #{} cat (vals (lookup/token-shape-ids objects)))
                       (into (set ids) (lookup/shapes-using objects (keys changed))))
            targets  (-> (cltp/out-of-date-targets objects resolved
                                                   :ids ids
                                                   :unassigned? (and editor (or opening? (nil? changed))))
                         (without-guarded objects resolved (get state ::guard #{})))
            position (queue-position state opts)]
        (cond
          (empty? targets)
          (rx/of (tokens-ready page-id))

          (pos? position)
          ;; Another session goes first; check again after a wait and take
          ;; over if the page is still out of date.
          (->> (rx/of (check-page (-> opts
                                      (dissoc :remote?)
                                      (assoc :ids (keys targets)))))
               (rx/delay (leader/wait-ms position)))

          :else
          (rx/concat
           (->> (rx/concat
                 (rx/of (dwtpr/add-work 1)
                        (add-to-guard targets objects resolved))
                 (if (dwtpr/long-work? objects targets)
                   ;; The dialog that blocks the workspace is painted before the work
                   (rx/concat
                    (rx/of (dwtpr/show))
                    (->> (rx/of (set-values page-id resolved targets))
                         (rx/delay dwtpr/paint-delay-ms)))
                   (try
                     (set-page-values it state page-id objects resolved targets)
                     (catch :default cause
                       (rx/throw cause))))
                 ;; The other sessions on the page wait for these values: they
                 ;; are saved now, not after the pause that groups the edits
                 (if (and editor remote?)
                   (rx/of ::dps/force-persist)
                   (rx/empty)))
                ;; A failure still ends the work, so the dialog closes, and
                ;; the shapes are not taken as set
                (rx/catch (fn [cause]
                            (l/err :hint "unable to set the token values of the page" :cause cause)
                            (rx/of (clear-guard (keys targets))))))
           (rx/of (dwtpr/work-done 1)
                  (tokens-ready page-id))))))))

(defn check-page
  "Set again the out of date token attributes of the current page: of the
  shapes `ids`, or of all of them without `ids`, and of the shapes that
  apply a token changed since the last check. Options:
  - `:opening?`: the page has just been opened; the unassigned attributes
    count as out of date for editors.
  - `:remote?`: what changed comes from other sessions, so this session
    only sets the values when it is the first in line, or after a wait.
  - `:author-id`: session of the last change of the tokens, first in line."
  [opts]
  (ptk/reify ::check-page
    ptk/WatchEvent
    (watch [_ state stream]
      (when-let [page-id (:current-page-id state)]
        (if (loading-shapes? state page-id)
          (->> stream
               (rx/filter (ptk/type? ::shapes-loaded))
               (rx/filter #(= page-id (deref %)))
               (rx/take 1)
               (rx/map (fn [_] (check-page opts)))
               (rx/take-until
                (rx/filter #(contains? #{::finalize ::dwpg/initialized} (ptk/type %))
                           stream)))
          (->> (resolved-tokens state)
               (rx/mapcat
                (fn [[resolved changed source]]
                  (rx/concat
                   (if (some? source)
                     (rx/of (set-snapshot source resolved opts))
                     (rx/empty))
                   (rx/of (set-out-of-date opts resolved changed)))))
               ;; Without tokens the page holds its values already
               (rx/if-empty (tokens-ready page-id))
               (rx/catch (fn [cause]
                           (l/err :hint "unable to check the token values" :cause cause)
                           (rx/of (tokens-ready page-id))))))))))

;; --- Watcher

(def ^:private shape-change-types
  #{:add-obj :mod-obj :mov-objects})

(defn- commit-info
  "What a commit may leave out of date: the token change, and the shapes it
  changes on any page."
  [{:keys [redo-changes source tags]}]
  {:tokens? (pv/tokens-change? redo-changes)
   :remote? (= :remote source)
   :local?  (= :local source)
   ;; The texts laid out again after a change, not an edit
   :layout? (contains? tags :position-data)
   :skip?   (or (contains? tags :token-propagation)
                (= :in-memory source))
   :ids     (into #{}
                  (comp (filter #(contains? shape-change-types (:type %)))
                        (mapcat (fn [{:keys [type id shapes]}]
                                  (if (= type :mov-objects) shapes [id]))))
                  redo-changes)})

(defn initialize
  "Watch the commits of `file-id` and set again the out of date token
  values of the current page after them, and when a page is opened."
  [file-id]
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [;; A workspace reload starts a new one, which replaces this one
            stopper   (rx/filter #(or (ptk/type? ::finalize %)
                                      (ptk/type? ::initialize %))
                                 stream)
            pending*  (volatile! {:ids #{} :remote? false})
            author*   (volatile! nil)

            infos
            (->> stream
                 (rx/filter dch/commit?)
                 (rx/map deref)
                 (rx/filter #(= file-id (:file-id %)))
                 (rx/map commit-info)
                 (rx/share))

            commits
            (rx/filter #(and (not (:skip? %))
                             (or (:tokens? %) (seq (:ids %))))
                       infos)]

        (->>
         (rx/merge
          ;; The author of the last remote change of the tokens goes first
          (->> stream
               (rx/filter (ptk/type? ::dwn/handle-file-change))
               (rx/map deref)
               (rx/filter #(pv/tokens-change? (:changes %)))
               (rx/tap #(vreset! author* (:session-id %)))
               (rx/ignore))

          ;; A change of the tokens goes with the values of the current page
          (->> infos
               (rx/filter :tokens?)
               (rx/map (fn [_] (reset-guard))))

          ;; A local edit may set again what this session set before
          (->> commits
               (rx/filter #(and (:local? %) (not (:layout? %))))
               (rx/filter #(seq (:ids %)))
               (rx/map #(clear-guard (:ids %))))

          (->> commits
               (rx/tap (fn [{:keys [ids tokens? remote?]}]
                         (vswap! pending* (fn [pending]
                                            (-> pending
                                                (update :ids into ids)
                                                (update :tokens? #(or % tokens?))
                                                (update :remote? #(or % remote?)))))))
               (rx/debounce leader/settle-ms)
               (rx/map (fn [_]
                         (let [{:keys [ids tokens? remote?]} @pending*
                               author-id @author*]
                           (vreset! pending* {:ids #{} :remote? false})
                           ;; The author goes first only for its change
                           (when tokens? (vreset! author* nil))
                           (check-page {:ids (if tokens? ids (seq ids))
                                        :remote? remote?
                                        :author-id author-id})))))

          (->> stream
               (rx/filter (ptk/type? ::dw/page-shapes-loaded))
               (rx/map #(shapes-loaded (:page-id (deref %)))))

          (->> stream
               (rx/filter (ptk/type? ::dwpg/initialized))
               (rx/mapcat (fn [event]
                            (rx/of (page-opened (deref event))
                                   (check-page {:opening? true})))))

          ;; The tokens of a library used as tokens source arrive after the
          ;; page opens
          (->> stream
               (rx/filter (ptk/type? ::dw/all-libraries-resolved))
               (rx/filter #(= file-id (:file-id (deref %))))
               (rx/map (fn [_] (check-page {:opening? true})))))
         (rx/take-until stopper))))))

(defn finalize
  []
  (ptk/reify ::finalize
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state ::snapshot ::guard ::opened-page-id ::shapes-page-id))))
