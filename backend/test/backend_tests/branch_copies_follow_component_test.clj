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
   [app.common.files.validate :as cfv]
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

(defn- setup
  "Main holding a local component (main `:mi-id`) with one copy
  (`:copy-id`), branched once. `n` numbers the profile and the file, so
  one test can build more than one."
  [n]
  (let [profile (th/create-profile* n {:is-active true})
        file    (th/create-file* n {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        main-id (:id file)
        page-id (-> (get-file profile main-id) :data :pages first)
        comp-id (uuid/random)
        mi-id   (uuid/random)
        copy-id (uuid/random)]
    (save profile main-id
          [(add-root page-id mi-id (component-frame mi-id {:component-root true
                                                           :main-instance true
                                                           :component-id comp-id
                                                           :component-file main-id}))
           {:type :add-component :id comp-id :name "Button" :path ""
            :main-instance-id mi-id :main-instance-page page-id}
           (copy-of page-id copy-id comp-id mi-id main-id)])
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
            (t/is (= #{mi-id} (conflict-ids out))))

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
            (t/is (= #{mi-id} (conflict-ids out))))

          (let [out (update-branch ctx :resolutions {mi-id side})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))

          (let [branch (get-file profile branch-file-id)]
            (t/is (= color (fill branch page-id mi-id)))
            (t/is (= color (fill branch page-id copy-id)))
            (t/is (empty? (:touched (shape branch page-id copy-id))))
            (t/is (empty? (validation-codes branch)))))))))

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
