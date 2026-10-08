;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-semantic-test
  (:require
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.semantic :as semantic]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FIXTURES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private page-id    #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private empty-page #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private login-id   #uuid "00000000-0000-0000-0000-0000000000f1")
(def ^:private home-id    #uuid "00000000-0000-0000-0000-0000000000f2")
(def ^:private comp-id    #uuid "00000000-0000-0000-0000-0000000000f3")
(def ^:private title-id   #uuid "00000000-0000-0000-0000-0000000000b1")
(def ^:private body-id    #uuid "00000000-0000-0000-0000-0000000000b2")
(def ^:private link-id    #uuid "00000000-0000-0000-0000-0000000000b3")
(def ^:private nav-id     #uuid "00000000-0000-0000-0000-0000000000b4")
(def ^:private image-id   #uuid "00000000-0000-0000-0000-0000000000b5")
(def ^:private comp-text  #uuid "00000000-0000-0000-0000-0000000000b6")
(def ^:private home-text  #uuid "00000000-0000-0000-0000-0000000000b7")

(defn- text-content
  [text]
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children [{:text text}]}]}]})

(defn- text-shape
  [id frame-id name text]
  (cts/setup-shape {:id id :type :text :name name
                    :parent-id frame-id :frame-id frame-id
                    :width 200 :height 24
                    :content (text-content text)}))

(defn- rect-shape
  [id frame-id attrs]
  (cts/setup-shape (merge {:id id :type :rect :name "rect"
                           :parent-id frame-id :frame-id frame-id
                           :width 100 :height 40}
                          attrs)))

(defn- frame-shape
  [id attrs]
  (cts/setup-shape (merge {:id id :type :frame :name "Frame"
                           :parent-id uuid/zero :frame-id uuid/zero
                           :width 300 :height 200}
                          attrs)))

(defn- image-shape
  [id frame-id]
  (cts/setup-shape {:id id :type :image :name "logo"
                    :parent-id frame-id :frame-id frame-id
                    :width 50 :height 50
                    :metadata {:width 50 :height 50}}))

(defn- root-shape
  [shapes]
  (assoc (cts/setup-shape {:id uuid/zero :type :frame :name "Root Frame"
                           :parent-id uuid/zero :frame-id uuid/zero})
         :shapes shapes))

(defn- fixture-file
  "A file with one phishing-looking screen, one benign screen and one
  component main instance that must be stripped."
  []
  (let [objects
        {uuid/zero (root-shape [login-id home-id comp-id])

         login-id  (assoc (frame-shape login-id {:name "Login form"})
                          :shapes [title-id body-id link-id nav-id image-id])
         home-id   (assoc (frame-shape home-id {:name "Home"})
                          :shapes [home-text])
         comp-id   (assoc (frame-shape comp-id {:name "Button" :main-instance true})
                          :shapes [comp-text])

         title-id  (text-shape title-id login-id "Title" "Your account has been suspended.")
         body-id   (text-shape body-id login-id "Body" "Verify your account within 24 hours.")
         link-id   (rect-shape link-id login-id
                               {:name "Verify"
                                :interactions [{:event-type :click
                                                :action-type :open-url
                                                :url "https://evil.example/verify"}]})
         nav-id    (rect-shape nav-id login-id
                               {:name "Go home"
                                :interactions [{:event-type :click
                                                :action-type :navigate
                                                :destination home-id}]})
         image-id  (image-shape image-id login-id)
         comp-text (text-shape comp-text comp-id "Main" "Main component label")
         home-text (text-shape home-text home-id "Home" "Welcome home")}

        page    {:id page-id :name "Login" :objects objects}
        empty   {:id empty-page :name "Empty" :objects {uuid/zero (root-shape [])}}

        data    {:pages [page-id empty-page]
                 :pages-index {page-id page
                               empty-page empty}}]

    {:id uuid/next :name "phish" :data data}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PURE PROJECTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest projection-keeps-semantic-signals
  (let [result (semantic/file->projection (fixture-file))]
    (t/testing "envelope"
      (t/is (= semantic/projection-version (:projection-version result)))
      (t/is (= "phish" (:file-name result))))

    (t/testing "empty pages are dropped"
      (t/is (= 1 (count (:pages result))))
      (t/is (= "Login" (get-in result [:pages 0 :name]))))

    (t/testing "component main instances are stripped, real frames are kept"
      (t/is (= #{"Login form" "Home"}
               (into #{} (map :name) (get-in result [:pages 0 :frames])))))

    (t/testing "text is gathered in tree order"
      (t/is (= ["Your account has been suspended."
                "Verify your account within 24 hours."]
               (get-in result [:pages 0 :frames 0 :text]))))

    (t/testing "open-url interactions become links"
      (t/is (= [{:text "Verify" :url "https://evil.example/verify"}]
               (get-in result [:pages 0 :frames 0 :links]))))

    (t/testing "destination interactions become navigations"
      (t/is (= [{:text "Go home" :action "navigate" :target "Home"}]
               (get-in result [:pages 0 :frames 0 :navigations]))))

    (t/testing "image count is reported"
      (t/is (= 1 (get-in result [:pages 0 :frames 0 :images]))))

    (t/testing "stats"
      (t/is (= {:pages 1 :frames 2 :text-nodes 3
                :links 1 :navigations 1 :images 1}
               (:stats result))))))

(t/deftest projection-drops-empty-frames
  (let [objects {uuid/zero (root-shape [login-id])
                 login-id  (assoc (frame-shape login-id {:name "Decor"}) :shapes [])}
        file    {:id uuid/next :name "empty"
                 :data {:pages [page-id]
                        :pages-index {page-id {:id page-id :name "P" :objects objects}}}}
        result  (semantic/file->projection file)]
    (t/is (= [] (:pages result)))
    (t/is (= {:pages 0 :frames 0 :text-nodes 0
              :links 0 :navigations 0 :images 0}
             (:stats result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RPC METHOD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out    (th/command! params)]
    (t/is (nil? (:error out)))
    (:result out)))

(t/deftest get-semantic-file-command
  (let [profile (th/create-profile* 1 {:is-active true})
        project (th/create-project* 1 {:team-id (:default-team-id profile)
                                       :profile-id (:id profile)})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        page-id (first (get-in file [:data :pages]))
        frame-id (uuid/next)
        text-id  (uuid/next)]

    (update-file!
     :profile-id (:id profile)
     :file-id (:id file)
     :revn 0
     :changes
     [{:type :add-obj :page-id page-id :id frame-id
       :parent-id uuid/zero :frame-id uuid/zero
       :obj (cts/setup-shape {:id frame-id :type :frame :name "Login"
                              :width 300 :height 200})}
      {:type :add-obj :page-id page-id :id text-id
       :parent-id frame-id :frame-id frame-id
       :obj (text-shape text-id frame-id "Label" "Sign in to continue")}])

    (t/testing "returns the semantic projection"
      (let [out (th/command! {::th/type :get-semantic-file
                              ::rpc/profile-id (:id profile)
                              :id (:id file)})]
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (= semantic/projection-version (:projection-version result)))
          (t/is (= (:id file) (:file-id result)))
          (t/is (= "file1" (:file-name result)))
          (t/is (= 1 (count (:pages result))))
          (t/is (= ["Sign in to continue"]
                   (get-in result [:pages 0 :frames 0 :text]))))))

    (t/testing "denies profiles without read access"
      (let [other (th/create-profile* 2 {:is-active true})
            out   (th/command! {::th/type :get-semantic-file
                                ::rpc/profile-id (:id other)
                                :id (:id file)})]
        (t/is (th/ex-info? (:error out)))
        (t/is (= :not-found (th/ex-type (:error out))))))))
