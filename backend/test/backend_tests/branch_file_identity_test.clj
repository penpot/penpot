;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.branch-file-identity-test
  "A branch file is its own file: the document a branch derives from
  main's base snapshot names the branch, in `:data :id` and in every
  reference to itself (component instances, library colours, the tokens
  source). The tests build main with a local component and a token set,
  branch it, and check each branch verb against that identity."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.tokens :as cfo]
   [app.common.files.validate :as cfv]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
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

(defn- set-fill
  [page-id id color]
  {:type :mod-obj :page-id page-id :id id
   :operations [{:type :set :attr :fills
                 :val [{:fill-color color :fill-opacity 1}]}]})

(defn- copy-of
  "A copy of the component `component-id` whose main is `main-id`, linked
  to the component through `component-file`."
  [page-id id component-id main-id component-file]
  (add-root page-id id (component-frame id {:component-root true
                                            :component-id component-id
                                            :component-file component-file
                                            :shape-ref main-id})))

(defn- painted
  "A rect painted with the library colour `color-id` of `file-id`, as a
  fill and as a shadow."
  [page-id id color-id file-id]
  (add-root page-id id
            (-> (cts/setup-shape {:id id :name "Swatch" :type :rect
                                  :x 0 :y 0 :width 10 :height 10
                                  :parent-id uuid/zero :frame-id uuid/zero})
                (assoc :fills [{:fill-color "#ff0000" :fill-opacity 1
                                :fill-color-ref-id color-id
                                :fill-color-ref-file file-id}])
                (assoc :shadow [{:id (uuid/random) :style :drop-shadow
                                 :offset-x 4 :offset-y 4 :blur 4 :spread 0
                                 :hidden false
                                 :color {:color "#ff0000" :opacity 1
                                         :ref-id color-id :ref-file file-id}}]))))

(defn- setup
  "Main holding a local component (main `:mi-id`, copy `:copy-id`), a
  library colour painted on `:swatch-id`, and a token set, branched
  once."
  []
  (let [profile   (th/create-profile* 1 {:is-active true})
        file      (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})
        main-id   (:id file)
        page-id   (-> (get-file profile main-id) :data :pages first)
        comp-id   (uuid/random)
        mi-id     (uuid/random)
        copy-id   (uuid/random)
        color-id  (uuid/random)
        swatch-id (uuid/random)
        set-id    (uuid/random)
        tok-id    (uuid/random)]
    (save profile main-id
          [(add-root page-id mi-id (component-frame mi-id {:component-root true
                                                           :main-instance true
                                                           :component-id comp-id
                                                           :component-file main-id}))
           {:type :add-component :id comp-id :name "Button" :path ""
            :main-instance-id mi-id :main-instance-page page-id}
           (copy-of page-id copy-id comp-id mi-id main-id)
           {:type :add-color
            :color {:id color-id :name "red" :color "#ff0000" :opacity 1}}
           (painted page-id swatch-id color-id main-id)
           {:type :set-token-set :id set-id :attrs {:id set-id :name "core"}}
           {:type :set-token :set-id set-id :token-id tok-id
            :attrs {:id tok-id :name "brand" :type :color :value "#ff0000"}}])
    (let [branch (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :file-id main-id
                                        :name "identity"}))]
      {:profile        profile
       :main-id        main-id
       :page-id        page-id
       :comp-id        comp-id
       :mi-id          mi-id
       :copy-id        copy-id
       :color-id       color-id
       :swatch-id      swatch-id
       :branch-id      (:id branch)
       :branch-file-id (:branch-file-id branch)})))

(defn- shape
  [file page-id id]
  (get-in file [:data :pages-index page-id :objects id]))

(defn- colour-refs
  [file page-id id]
  (let [s (shape file page-id id)]
    [(-> s :fills first :fill-color-ref-file)
     (-> s :shadow first :color :ref-file)]))

(defn- validation-codes
  [file]
  (let [libs (db/run! th/*system* #(bfc/get-resolved-file-libraries % file))]
    (into #{} (map :code) (cfv/validate-file file libs))))

(t/deftest an-opened-branch-names-itself
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile page-id mi-id copy-id swatch-id branch-file-id]} (setup)
          branch (get-file profile branch-file-id)]

      (t/testing "the document carries the branch file's id"
        (t/is (= branch-file-id (-> branch :data :id))))

      (t/testing "inherited instances resolve their component locally"
        (t/is (= branch-file-id (:component-file (shape branch page-id mi-id))))
        (t/is (= branch-file-id (:component-file (shape branch page-id copy-id))))
        (t/is (= branch-file-id
                 (:id (ctf/find-component-file branch {}
                                               (:component-file (shape branch page-id copy-id)))))))

      (t/testing "inherited fills and shadows name the branch's own colour"
        (t/is (= [branch-file-id branch-file-id] (colour-refs branch page-id swatch-id))))

      (t/testing "the branch is its own tokens source"
        (t/is (= branch-file-id (cfo/get-effective-tokens-source (:data branch)))))

      (t/testing "the branch validates clean"
        (t/is (empty? (validation-codes branch)))))))

(t/deftest update-from-main-keeps-a-branch-copy-of-a-base-component
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id page-id comp-id mi-id copy-id branch-id branch-file-id]} (setup)
          own-copy (uuid/random)]
      ;; the workspace instantiates a local component with the current
      ;; file, the branch, as its library
      (save profile branch-file-id [(copy-of page-id own-copy comp-id mi-id branch-file-id)])
      (save profile main-id [(set-fill page-id mi-id "#0000ff")
                             (set-fill page-id copy-id "#0000ff")])

      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [branch (get-file profile branch-file-id)]
        (t/is (= "#0000ff" (-> (shape branch page-id mi-id) :fills first :fill-color)))
        (t/is (= branch-file-id (:component-file (shape branch page-id own-copy))))
        (t/is (empty? (validation-codes branch)))))))

(t/deftest merge-names-main-in-what-it-applies
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id page-id comp-id mi-id color-id branch-id branch-file-id]} (setup)
          own-copy   (uuid/random)
          own-swatch (uuid/random)]
      ;; the workspace paints with a local colour naming the current
      ;; file, the branch
      (save profile branch-file-id [(copy-of page-id own-copy comp-id mi-id branch-file-id)
                                    (painted page-id own-swatch color-id branch-file-id)
                                    (set-fill page-id mi-id "#00ff00")])

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [main (get-file profile main-id)]
        (t/is (= main-id (-> main :data :id)))
        (t/is (= main-id (:component-file (shape main page-id own-copy))))
        (t/is (= main-id (:component-file (shape main page-id mi-id))))
        (t/is (= "#00ff00" (-> (shape main page-id mi-id) :fills first :fill-color)))
        (t/is (= [main-id main-id] (colour-refs main page-id own-swatch)))
        (t/is (empty? (validation-codes main)))))))

(t/deftest materialize-keeps-the-base-components
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile page-id comp-id mi-id copy-id branch-file-id]} (setup)
          out (th/command! {::th/type :materialize-file-branch
                            ::rpc/profile-id (:id profile)
                            :branch-file-id branch-file-id})]
      (t/is (nil? (:error out)))

      (let [file (get-file profile branch-file-id)
            main (shape file page-id mi-id)]
        (t/is (= branch-file-id (-> file :data :id)))
        (t/is (true? (:main-instance main)))
        (t/is (= comp-id (:component-id main)))
        (t/is (= branch-file-id (:component-file main)))
        (t/is (= branch-file-id (:component-file (shape file page-id copy-id))))
        (t/is (empty? (validation-codes file)))))))

(t/deftest materialize-owns-the-images-it-inherited
  ;; a materialized file outlives its source's media rows only if it owns
  ;; copies: main's file-gc and main's deletion take main's rows along
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})
          main-id (:id file)
          page-id (-> (get-file profile main-id) :data :pages first)
          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))
          sobj    (sto/put-object! storage {::sto/content (sto/content "photo")
                                            :bucket :file-media-object
                                            :content-type "image/png"})
          fmo     (th/create-file-media-object* {:file-id main-id
                                                 :name "photo.png"
                                                 :mtype "image/png"
                                                 :media-id (:id sobj)})
          photo   (uuid/random)
          _       (save profile main-id
                        [(add-root page-id photo
                                   (-> (cts/setup-shape {:id photo :name "Photo" :type :rect
                                                         :x 0 :y 0 :width 100 :height 100
                                                         :parent-id uuid/zero :frame-id uuid/zero})
                                       (assoc :fills [{:fill-opacity 1
                                                       :fill-image {:id (:id fmo) :width 100 :height 100
                                                                    :mtype "image/png"}}])))])
          branch-file-id (-> (th/command! {::th/type :create-file-branch
                                           ::rpc/profile-id (:id profile)
                                           :file-id main-id
                                           :name "photos"})
                             :result :branch-file-id)
          out     (th/command! {::th/type :materialize-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-file-id branch-file-id})]
      (t/is (nil? (:error out)))

      (let [image-id (-> (get-file profile branch-file-id)
                         (shape page-id photo)
                         :fills first :fill-image :id)
            row      (th/db-get :file-media-object {:id image-id})]
        (t/is (not= (:id fmo) image-id))
        (t/is (= branch-file-id (:file-id row)))
        (t/is (= (:id sobj) (:media-id row)))))))
