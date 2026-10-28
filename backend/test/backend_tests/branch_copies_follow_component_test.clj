;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.branch-copies-follow-component-test
  "Copies follow their merged component. When a branch is merged into
  main, or main's changes are brought into the branch, the copies of a
  local component end up as Penpot's component sync leaves them after
  the integrated component: an attribute no side overrode by hand follows
  the component, an attribute one side overrode keeps that side's value,
  and only an attribute both sides overrode differently is a conflict.

  Each test builds main with a local component (main `:mi-id`) and one
  copy of it (`:copy-id`), branches it, edits both sides and integrates.
  A component edit writes the main and, as the workspace's sync does,
  the untouched copy with `:ignore-touched`; an override writes the copy
  alone, which marks it touched."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.validate :as cfv]
   [app.common.logic.libraries :as cll]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- save
  "Apply `changes` to `file-id` through the RPC, as the workspace does."
  [profile file-id changes]
  (let [file (th/db-get :file {:id file-id})
        out  (th/command! {::th/type :update-file
                           ::rpc/profile-id (:id profile)
                           :id file-id
                           :session-id (uuid/random)
                           :revn (:revn file)
                           :vern (:vern file)
                           :features cfeat/supported-features
                           :changes changes})]
    (t/is (nil? (:error out)))))

(defn- get-file
  [profile file-id]
  (:result (th/command! {::th/type :get-file
                         ::rpc/profile-id (:id profile)
                         :id file-id})))

(defn- component-frame
  [id attrs]
  (merge (cts/setup-shape {:id id :name "Button" :type :frame
                           :x 0 :y 0 :width 100 :height 100
                           :parent-id uuid/zero :frame-id uuid/zero})
         attrs))

(defn- add-root
  [page-id id obj]
  {:type :add-obj :page-id page-id :id id
   :parent-id uuid/zero :frame-id uuid/zero :obj obj})

(defn- fills
  [color]
  [{:fill-color color :fill-opacity 1}])

(defn- set-fill
  "A hand edit of the fill of `id`: on a copy it marks the fill
  overridden."
  [page-id id color]
  {:type :mod-obj :page-id page-id :id id
   :operations [{:type :set :attr :fills :val (fills color)}]})

(defn- sync-fill
  "The fill the workspace's component sync writes on an untouched copy."
  [page-id id color]
  {:type :mod-obj :page-id page-id :id id
   :operations [{:type :set :attr :fills :val (fills color) :ignore-touched true}]})

(defn- copy-of
  [page-id id component-id main-id component-file]
  (add-root page-id id (component-frame id {:component-root true
                                            :component-id component-id
                                            :component-file component-file
                                            :shape-ref main-id})))

(defn- edit-component
  "Paint the component's main `color` and sync the copy `copy-id`."
  [page-id mi-id copy-id color]
  [(set-fill page-id mi-id color)
   (sync-fill page-id copy-id color)])

(def ^:private token-colors
  "The colour tokens `setup` adds to main, by name."
  {"green" "#00ff00" "blue" "#0000ff" "red" "#ff0000" "yellow" "#ffff00"})

(defn- apply-fill-token
  "Apply the colour token `token-name` to the fill of `id` by hand, as
  the workspace's token application does: the token goes into
  `:applied-tokens` (on a copy this marks the fill overridden) and its
  colour into the fill without marking anything."
  [page-id id token-name]
  {:type :mod-obj :page-id page-id :id id
   :operations [{:type :set :attr :applied-tokens :val {:fill token-name}}
                {:type :set :attr :fills :val (fills (get token-colors token-name))
                 :ignore-touched true}]})

(defn- sync-fill-token
  "The token and fill the workspace's component sync writes on an
  untouched copy."
  [page-id id token-name]
  {:type :mod-obj :page-id page-id :id id
   :operations [{:type :set :attr :applied-tokens :val {:fill token-name} :ignore-touched true}
                {:type :set :attr :fills :val (fills (get token-colors token-name))
                 :ignore-touched true}]})

(defn- tokenize-component
  "Apply the token `token-name` to the component's main fill and sync the
  copy `copy-id`."
  [page-id mi-id copy-id token-name]
  [(apply-fill-token page-id mi-id token-name)
   (sync-fill-token page-id copy-id token-name)])

(defn- setup
  "Main holding a local component (main `:mi-id`) with one copy
  (`:copy-id`) and a token set with the colour tokens `token-colors`,
  branched once. `n` numbers the profile and the file, so one test can
  build more than one. With `:override`, the copy's fill is overridden
  to that colour before branching, so the base holds the override."
  [n & {:keys [override]}]
  (let [profile (th/create-profile* n {:is-active true})
        file    (th/create-file* n {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        main-id (:id file)
        page-id (-> (get-file profile main-id) :data :pages first)
        comp-id (uuid/random)
        mi-id   (uuid/random)
        copy-id (uuid/random)
        set-id  (uuid/random)]
    (save profile main-id
          [(add-root page-id mi-id (component-frame mi-id {:component-root true
                                                           :main-instance true
                                                           :component-id comp-id
                                                           :component-file main-id}))
           {:type :add-component :id comp-id :name "Button" :path ""
            :main-instance-id mi-id :main-instance-page page-id}
           (copy-of page-id copy-id comp-id mi-id main-id)
           {:type :set-token-set :id set-id :attrs {:id set-id :name "core"}}])
    (save profile main-id
          (mapv (fn [[token-name color]]
                  (let [token-id (uuid/random)]
                    {:type :set-token :set-id set-id :token-id token-id
                     :attrs {:id token-id :name token-name :type :color :value color}}))
                token-colors))
    (when override
      (save profile main-id [(set-fill page-id copy-id override)]))
    (let [branch (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :file-id main-id
                                        :name "copies"}))]
      {:profile        profile
       :main-id        main-id
       :page-id        page-id
       :comp-id        comp-id
       :mi-id          mi-id
       :copy-id        copy-id
       :branch-id      (:id branch)
       :branch-file-id (:branch-file-id branch)})))

(defn- merge-branch
  [{:keys [profile branch-id]} & {:as params}]
  (th/command! (merge {::th/type :merge-file-branch
                       ::rpc/profile-id (:id profile)
                       :branch-id branch-id}
                      params)))

(defn- update-branch
  [{:keys [profile branch-id]} & {:as params}]
  (th/command! (merge {::th/type :update-branch-from-main
                       ::rpc/profile-id (:id profile)
                       :branch-id branch-id}
                      params)))

(defn- shape
  [file page-id id]
  (get-in file [:data :pages-index page-id :objects id]))

(defn- fill
  [file page-id id]
  (-> (shape file page-id id) :fills first :fill-color))

(defn- validation-codes
  [file]
  (let [libs (db/run! th/*system* #(bfc/get-resolved-file-libraries % file))]
    (into #{} (map :code) (cfv/validate-file file libs))))

(defn- conflict-ids
  [out]
  (into #{} (map :id) (-> out :result :conflicts)))

;; --- a: one side edits the component, the other adds a copy

(t/deftest merge-syncs-a-copy-main-added-to-the-branch-component
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id comp-id mi-id copy-id] :as ctx} (setup 1)
          new-copy (uuid/random)]
      (save profile branch-file-id (edit-component page-id mi-id copy-id "#00ff00"))
      (save profile main-id [(copy-of page-id new-copy comp-id mi-id main-id)])

      (let [out (merge-branch ctx)]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [main (get-file profile main-id)]
        (t/is (= "#00ff00" (fill main page-id mi-id)))
        (t/is (= "#00ff00" (fill main page-id new-copy)))
        (t/is (= "#00ff00" (fill main page-id copy-id)))
        (t/is (empty? (:touched (shape main page-id copy-id))))
        (t/is (empty? (validation-codes main)))))))

(t/deftest update-syncs-a-copy-the-branch-added-to-main-component
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id comp-id mi-id copy-id] :as ctx} (setup 1)
          new-copy (uuid/random)]
      (save profile main-id (edit-component page-id mi-id copy-id "#0000ff"))
      (save profile branch-file-id [(copy-of page-id new-copy comp-id mi-id branch-file-id)])

      (let [out (update-branch ctx)]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [branch (get-file profile branch-file-id)]
        (t/is (= "#0000ff" (fill branch page-id mi-id)))
        (t/is (= "#0000ff" (fill branch page-id new-copy)))
        (t/is (= "#0000ff" (fill branch page-id copy-id)))
        (t/is (empty? (:touched (shape branch page-id copy-id))))
        (t/is (empty? (validation-codes branch)))))))

;; --- b: both sides edit the component, each syncs its copy

(defn- both-edit-component
  [{:keys [profile main-id branch-file-id page-id mi-id copy-id]}]
  (save profile branch-file-id (edit-component page-id mi-id copy-id "#00ff00"))
  (save profile main-id (edit-component page-id mi-id copy-id "#0000ff")))

(t/deftest merge-of-a-component-both-sides-edited-conflicts-on-the-component-only
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (doseq [[side color] [[:branch "#00ff00"] [:main "#0000ff"]]]
      (t/testing (str "resolved to " side)
        (let [{:keys [profile main-id page-id mi-id copy-id] :as ctx} (setup (if (= :branch side) 1 2))]
          (both-edit-component ctx)

          (let [out (merge-branch ctx)]
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= #{mi-id} (conflict-ids out)))
            (t/testing "the response names the copies the resolution reaches"
              (t/is (= [[mi-id 1]] (mapv (juxt :id :copies-affected) (-> out :result :conflicts))))
              (t/is (= [#{copy-id}] (mapv #(into #{} (map :id) (:copies %)) (-> out :result :copies))))))

          (let [out (merge-branch ctx :resolutions {mi-id side})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status))))

          (let [main (get-file profile main-id)]
            (t/is (= color (fill main page-id mi-id)))
            (t/is (= color (fill main page-id copy-id)))
            (t/is (empty? (:touched (shape main page-id copy-id))))
            (t/is (empty? (validation-codes main)))))))))

(t/deftest update-of-a-component-both-sides-edited-conflicts-on-the-component-only
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (doseq [[side color] [[:branch "#00ff00"] [:main "#0000ff"]]]
      (t/testing (str "resolved to " side)
        (let [{:keys [profile branch-file-id page-id mi-id copy-id] :as ctx} (setup (if (= :branch side) 1 2))]
          (both-edit-component ctx)

          (let [out (update-branch ctx)]
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= #{mi-id} (conflict-ids out)))
            (t/is (= [[mi-id 1]] (mapv (juxt :id :copies-affected) (-> out :result :conflicts))))
            (t/is (= [#{copy-id}] (mapv #(into #{} (map :id) (:copies %)) (-> out :result :copies)))))

          (let [out (update-branch ctx :resolutions {mi-id side})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))

          (let [branch (get-file profile branch-file-id)]
            (t/is (= color (fill branch page-id mi-id)))
            (t/is (= color (fill branch page-id copy-id)))
            (t/is (empty? (:touched (shape branch page-id copy-id))))
            (t/is (empty? (validation-codes branch)))))))))

(t/deftest the-diff-shows-a-copy-the-rule-settled
  ;; both sides synced the copy from their own edit of the component:
  ;; the compare summary shows the copy as settled, following the
  ;; component, and lists it among the component's copies
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile branch-id comp-id mi-id copy-id] :as ctx} (setup 1)]
      (both-edit-component ctx)
      (let [out   (th/command! {::th/type :get-branch-diff
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})
            diff  (:result out)
            entry (first (filter #(= copy-id (:id %)) (:changes diff)))]
        (t/is (nil? (:error out)))
        (t/is (= :settled (:status entry)))
        (t/is (= {:side :main :reason :follows-component} (get-in entry [:settled :attrs :fills])))
        (t/is (= [[mi-id 1]] (mapv (juxt :id :copies-affected) (:conflicts diff))))
        (t/is (= [[comp-id mi-id #{copy-id}]]
                 (mapv (juxt :component-id :main-id #(into #{} (map :id) (:copies %)))
                       (:copies diff))))))))

;; --- c: one side overrides the copy, the other edits the component

(t/deftest merge-keeps-the-branch-override-of-a-copy
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id mi-id copy-id] :as ctx} (setup 1)]
      (save profile branch-file-id [(set-fill page-id copy-id "#ff0000")])
      (save profile main-id (edit-component page-id mi-id copy-id "#0000ff"))

      (let [out (merge-branch ctx)]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [main (get-file profile main-id)]
        (t/is (= "#0000ff" (fill main page-id mi-id)))
        (t/is (= "#ff0000" (fill main page-id copy-id)))
        (t/is (contains? (:touched (shape main page-id copy-id)) :fill-group))
        (t/is (empty? (validation-codes main)))))))

(t/deftest update-keeps-main-override-of-a-copy
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id mi-id copy-id] :as ctx} (setup 1)]
      (save profile main-id [(set-fill page-id copy-id "#ff0000")])
      (save profile branch-file-id (edit-component page-id mi-id copy-id "#00ff00"))

      (let [out (update-branch ctx)]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [branch (get-file profile branch-file-id)]
        (t/is (= "#00ff00" (fill branch page-id mi-id)))
        (t/is (= "#ff0000" (fill branch page-id copy-id)))
        (t/is (contains? (:touched (shape branch page-id copy-id)) :fill-group))
        (t/is (empty? (validation-codes branch)))))))

;; --- d: both sides override the copy differently

(t/deftest merge-of-a-copy-both-sides-overrode-is-a-conflict
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id copy-id] :as ctx} (setup 1)]
      (save profile branch-file-id [(set-fill page-id copy-id "#ff0000")])
      (save profile main-id [(set-fill page-id copy-id "#ffff00")])

      (let [out (merge-branch ctx)]
        (t/is (= :conflicts (-> out :result :status)))
        (t/is (= #{copy-id} (conflict-ids out)))))))

(t/deftest update-of-a-copy-both-sides-overrode-is-a-conflict
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id copy-id] :as ctx} (setup 1)]
      (save profile branch-file-id [(set-fill page-id copy-id "#ff0000")])
      (save profile main-id [(set-fill page-id copy-id "#ffff00")])

      (let [out (update-branch ctx)]
        (t/is (= :conflicts (-> out :result :status)))
        (t/is (= #{copy-id} (conflict-ids out)))))))

;; --- e: one side resets an override the base holds, the other leaves
;; the copy alone

(defn- reset-copy
  "Reset the overrides of the copy `copy-id` in `file-id`, as the
  workspace's \"reset overrides\" does (`cll/generate-reset-component`)."
  [profile file-id page-id copy-id]
  (let [file    (get-file profile file-id)
        page    (ctn/make-container (get-in file [:data :pages-index page-id]) :page)
        changes (cll/generate-reset-component (pcb/empty-changes) file {file-id file} page copy-id)]
    (save profile file-id (:redo-changes changes))))

(defn- follows-component?
  "True when an edit of the component's fill on `file-id` reaches the
  copy through the component sync the workspace runs after the edit
  (`cll/generate-sync-file-changes`)."
  [profile file-id page-id comp-id mi-id copy-id]
  (save profile file-id [(set-fill page-id mi-id "#123456")])
  (let [file    (get-file profile file-id)
        changes (cll/generate-sync-file-changes (pcb/empty-changes) nil :components file-id
                                                comp-id file-id {file-id file} file-id)]
    (save profile file-id (:redo-changes changes))
    (= "#123456" (fill (get-file profile file-id) page-id copy-id))))

(t/deftest merge-keeps-a-reset-of-an-override-the-base-holds
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id comp-id mi-id copy-id] :as ctx}
          (setup 1 :override "#ff0000")]
      (reset-copy profile branch-file-id page-id copy-id)
      (t/testing "the reset clears the override on the branch"
        (let [branch (get-file profile branch-file-id)]
          (t/is (not (contains? (:touched (shape branch page-id copy-id)) :fill-group)))
          (t/is (= (fill branch page-id mi-id) (fill branch page-id copy-id)))))

      (let [out (merge-branch ctx)]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [main (get-file profile main-id)]
        (t/is (not (contains? (:touched (shape main page-id copy-id)) :fill-group)))
        (t/is (= (fill main page-id mi-id) (fill main page-id copy-id)))
        (t/is (empty? (validation-codes main))))
      (t/is (follows-component? profile main-id page-id comp-id mi-id copy-id)))))

(t/deftest update-keeps-a-reset-of-an-override-the-base-holds
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-file-id page-id comp-id mi-id copy-id] :as ctx}
          (setup 1 :override "#ff0000")]
      (reset-copy profile main-id page-id copy-id)
      (t/testing "the reset clears the override on main"
        (let [main (get-file profile main-id)]
          (t/is (not (contains? (:touched (shape main page-id copy-id)) :fill-group)))
          (t/is (= (fill main page-id mi-id) (fill main page-id copy-id)))))

      (let [out (update-branch ctx)]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [branch (get-file profile branch-file-id)]
        (t/is (not (contains? (:touched (shape branch page-id copy-id)) :fill-group)))
        (t/is (= (fill branch page-id mi-id) (fill branch page-id copy-id)))
        (t/is (empty? (validation-codes branch))))
      (t/is (follows-component? profile branch-file-id page-id comp-id mi-id copy-id)))))

;; --- tokens: the rule per applied-token slot

(defn- fill-token
  [file page-id id]
  (get-in (shape file page-id id) [:applied-tokens :fill]))

(defn- integrate
  "Merge the branch (`:merge`) or update it from main (`:update`), and
  return the result and the file the integration wrote to."
  [dir {:keys [profile main-id branch-file-id] :as ctx} & {:as params}]
  (case dir
    :merge  [(merge-branch ctx params) (get-file profile main-id)]
    :update [(update-branch ctx params) (get-file profile branch-file-id)]))

(def ^:private ok-status
  {:merge :merged :update :updated})

(t/deftest a-token-both-sides-applied-to-the-component-conflicts-on-it-only
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (doseq [[n dir side token] [[1 :merge :branch "green"] [2 :merge :main "blue"]
                                [3 :update :branch "green"] [4 :update :main "blue"]]]
      (t/testing (str dir " resolved to " side)
        (let [{:keys [profile main-id branch-file-id page-id mi-id copy-id] :as ctx} (setup n)]
          (save profile branch-file-id (tokenize-component page-id mi-id copy-id "green"))
          (save profile main-id (tokenize-component page-id mi-id copy-id "blue"))

          (let [[out] (integrate dir ctx)]
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= #{mi-id} (conflict-ids out))))

          (let [[out file] (integrate dir ctx :resolutions {mi-id side})]
            (t/is (nil? (:error out)))
            (t/is (= (ok-status dir) (-> out :result :status)))
            (t/is (= token (fill-token file page-id mi-id)))
            (t/is (= token (fill-token file page-id copy-id)))
            (t/is (= (get token-colors token) (fill file page-id copy-id)))
            (t/is (empty? (:touched (shape file page-id copy-id))))
            (t/is (empty? (validation-codes file)))))))))

(t/deftest a-token-one-side-applied-to-a-copy-by-hand-stays
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (doseq [[n dir] [[1 :merge] [2 :update]]]
      (t/testing (str dir)
        (let [{:keys [profile main-id branch-file-id page-id mi-id copy-id] :as ctx} (setup n)
              ;; the source overrides the copy, the target edits the component
              [source target] (if (= :merge dir)
                                [branch-file-id main-id]
                                [main-id branch-file-id])]
          (save profile source [(apply-fill-token page-id copy-id "red")])
          (save profile target (tokenize-component page-id mi-id copy-id "blue"))

          (let [[out file] (integrate dir ctx)]
            (t/is (nil? (:error out)))
            (t/is (= (ok-status dir) (-> out :result :status)))
            (t/is (= "blue" (fill-token file page-id mi-id)))
            (t/is (= "red" (fill-token file page-id copy-id)))
            (t/is (= "#ff0000" (fill file page-id copy-id)))
            (t/is (contains? (:touched (shape file page-id copy-id)) :fill-group))
            (t/is (empty? (validation-codes file)))))))))

(t/deftest a-token-both-sides-applied-to-a-copy-by-hand-is-a-conflict
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (doseq [[n dir] [[1 :merge] [2 :update]]]
      (t/testing (str dir)
        (let [{:keys [profile main-id branch-file-id page-id copy-id] :as ctx} (setup n)]
          (save profile branch-file-id [(apply-fill-token page-id copy-id "red")])
          (save profile main-id [(apply-fill-token page-id copy-id "yellow")])

          (let [[out] (integrate dir ctx)]
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= #{copy-id} (conflict-ids out)))))))))

(t/deftest a-token-applied-to-the-component-reaches-a-copy-the-other-side-added
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (doseq [[n dir] [[1 :merge] [2 :update]]]
      (t/testing (str dir)
        (let [{:keys [profile main-id branch-file-id page-id comp-id mi-id copy-id] :as ctx} (setup n)
              new-copy (uuid/random)
              ;; the source tokenizes the component, the target adds a copy
              [source target] (if (= :merge dir)
                                [branch-file-id main-id]
                                [main-id branch-file-id])]
          (save profile source (tokenize-component page-id mi-id copy-id "green"))
          (save profile target [(copy-of page-id new-copy comp-id mi-id target)])

          (let [[out file] (integrate dir ctx)]
            (t/is (nil? (:error out)))
            (t/is (= (ok-status dir) (-> out :result :status)))
            (t/is (= "green" (fill-token file page-id new-copy)))
            (t/is (= "#00ff00" (fill file page-id new-copy)))
            (t/is (empty? (:touched (shape file page-id new-copy))))
            (t/is (empty? (validation-codes file)))))))))
