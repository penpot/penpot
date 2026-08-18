;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-assets-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as-alias http]
   [app.http.access-token :as actoken]
   [app.http.assets :as assets]
   [app.http.session :as session]
   [app.rpc.commands.access-token :as access-token]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- configure-storage-backend
  "Given storage map, returns a storage configured with the
  appropriate backend for assets."
  [storage]
  (assoc storage ::sto/backend :fs))

(defn- create-storage-object!
  "Create a storage object with the given bucket and content."
  [storage bucket content]
  (sto/put-object! storage {::sto/content (sto/content content)
                            :bucket bucket
                            :content-type "text/plain"}))

(defn- make-handler-cfg
  "Build a minimal cfg map for the assets handlers."
  [storage]
  {::sto/storage storage
   ::assets/path "/assets"})

;; ----------------------------------------------------------------
;; Tests: get-id
;; ----------------------------------------------------------------

(t/deftest get-id-with-valid-uuid
  (let [id      (uuid/next)
        request {:path-params {:id (str id)}}
        result  (assets/get-id request)]
    (t/is (= id result))))

(t/deftest get-id-with-invalid-uuid
  (let [request {:path-params {:id "not-a-uuid"}}]
    (try
      (assets/get-id request)
      (t/is false "should have thrown")
      (catch Exception e
        (t/is (= :not-found (:type (ex-data e))))))))

(t/deftest get-id-with-missing-id
  (let [request {:path-params {}}]
    (try
      (assets/get-id request)
      (t/is false "should have thrown")
      (catch Exception e
        (t/is (= :not-found (:type (ex-data e))))))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — non-existent objects
;; ----------------------------------------------------------------

(t/deftest objects-handler-non-existent-object
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        cfg     (make-handler-cfg storage)
        request {:path-params {:id (str (uuid/next))}}
        response (assets/objects-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))

(t/deftest objects-handler-invalid-uuid
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        cfg     (make-handler-cfg storage)
        request {:path-params {:id "not-a-uuid"}}]
    (try
      (assets/objects-handler cfg request)
      (t/is false "should have thrown")
      (catch Exception e
        (t/is (= :not-found (:type (ex-data e))))))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — public buckets (no auth required)
;; ----------------------------------------------------------------

(t/deftest objects-handler-public-bucket-no-auth
  ;; Objects in public buckets should be accessible without authentication.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)]

    (doseq [bucket ["file-media-object"
                    "file-object-thumbnail"
                    "team-font-variant"
                    "file-data-fragment"
                    "organization"]]
      (t/testing (str "bucket: " bucket)
        (let [object  (create-storage-object! storage bucket "public data")
              request {:path-params {:id (str (:id object))}}
              response (assets/objects-handler cfg request)]
          (t/is (not= 401 (::yres/status response))
                (str "bucket " bucket " should not require auth"))
          (t/is (not= 404 (::yres/status response))
                (str "bucket " bucket " object should exist")))))))

(t/deftest objects-handler-organization-logo-no-auth
  ;; Organization logos are embedded in unauthenticated contexts, such as
  ;; the invitation email image shown to a not-yet-registered invitee, so
  ;; they must be servable without a session or access token.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        object   (create-storage-object! storage "organization" "logo data")
        request  {:path-params {:id (str (:id object))}}
        response (assets/objects-handler cfg request)]
    (t/is (not= 401 (::yres/status response)))
    (t/is (not= 404 (::yres/status response)))))

(t/deftest objects-handler-public-bucket-with-auth
  ;; Objects in public buckets should also be accessible WITH authentication.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        object   (create-storage-object! storage "file-media-object" "public data")
        request  {:path-params {:id (str (:id object))}
                  ::session/profile-id (:id profile)}
        response (assets/objects-handler cfg request)]
    (t/is (not= 401 (::yres/status response)))
    (t/is (not= 404 (::yres/status response)))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — non-public buckets (auth required)
;; ----------------------------------------------------------------

(t/deftest objects-handler-non-public-bucket-no-auth
  ;; Objects in non-public buckets should return 401 without authentication.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        object   (create-storage-object! storage "profile" "profile photo")
        request  {:path-params {:id (str (:id object))}}
        response (assets/objects-handler cfg request)]
    (t/is (= 401 (::yres/status response)))))

(t/deftest objects-handler-non-public-bucket-with-session-auth
  ;; Objects in non-public buckets should be accessible with session auth.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        object   (create-storage-object! storage "profile" "profile photo")
        request  {:path-params {:id (str (:id object))}
                  ::session/profile-id (:id profile)}
        response (assets/objects-handler cfg request)]
    (t/is (not= 401 (::yres/status response)))
    (t/is (not= 404 (::yres/status response)))))

(t/deftest objects-handler-non-public-bucket-with-access-token-auth
  ;; Objects in non-public buckets should be accessible with access token auth.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        object   (create-storage-object! storage "profile" "profile photo")
        request  {:path-params {:id (str (:id object))}
                  ::actoken/profile-id (:id profile)}
        response (assets/objects-handler cfg request)]
    (t/is (not= 401 (::yres/status response)))
    (t/is (not= 404 (::yres/status response)))))

(t/deftest objects-handler-non-public-bucket-with-both-auth
  ;; Objects should be accessible when both session and access token auth are present.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        object   (create-storage-object! storage "profile" "profile photo")
        request  {:path-params {:id (str (:id object))}
                  ::session/profile-id (:id profile)
                  ::actoken/profile-id (:id profile)}
        response (assets/objects-handler cfg request)]
    (t/is (not= 401 (::yres/status response)))
    (t/is (not= 404 (::yres/status response)))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — all non-public buckets
;; ----------------------------------------------------------------

(t/deftest objects-handler-all-non-public-buckets-require-auth
  ;; Verify that all buckets NOT in the public set require authentication.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)]

    (doseq [bucket ["profile"
                    "tempfile"
                    "file-data"
                    "file-thumbnail"
                    "file-change"]]
      (t/testing (str "bucket: " bucket)
        (let [object   (create-storage-object! storage bucket "some data")
              request  {:path-params {:id (str (:id object))}}]

          ;; Without auth → 401
          (let [response (assets/objects-handler cfg request)]
            (t/is (= 401 (::yres/status response))
                  (str "bucket " bucket " should require auth")))

          ;; With session auth → not 401
          (let [response (assets/objects-handler cfg (assoc request ::session/profile-id (:id profile)))]
            (t/is (not= 401 (::yres/status response))
                  (str "bucket " bucket " should be accessible with session auth")))

          ;; With access token auth → not 401
          (let [response (assets/objects-handler cfg (assoc request ::actoken/profile-id (:id profile)))]
            (t/is (not= 401 (::yres/status response))
                  (str "bucket " bucket " should be accessible with access token auth"))))))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — serve-object response (FS backend)
;; ----------------------------------------------------------------

(t/deftest objects-handler-fs-backend-serves-object
  ;; Verify that the FS backend returns the correct response structure.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        object   (create-storage-object! storage "file-media-object" "file content")
        request  {:path-params {:id (str (:id object))}
                  ::session/profile-id (:id profile)}
        response (assets/objects-handler cfg request)]
    ;; FS backend returns 204 with x-accel-redirect header
    (t/is (= 204 (::yres/status response)))
    (t/is (some? (get (::yres/headers response) "x-accel-redirect")))
    (t/is (= "text/plain" (get (::yres/headers response) "content-type")))
    (t/is (some? (get (::yres/headers response) "cache-control")))))

(t/deftest objects-handler-fs-backend-accel-redirect-path
  ;; Verify that x-accel-redirect contains the object's relative path.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        object   (create-storage-object! storage "file-media-object" "file content")
        request  {:path-params {:id (str (:id object))}}
        response (assets/objects-handler cfg request)
        redirect (get (::yres/headers response) "x-accel-redirect")]
    ;; The redirect path should contain the object's relative path
    (t/is (string? redirect))
    (t/is (clojure.string/includes? redirect (sto/object->relative-path object)))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — cache headers
;; ----------------------------------------------------------------

(t/deftest objects-handler-cache-control-header
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        object   (create-storage-object! storage "file-media-object" "content")
        request  {:path-params {:id (str (:id object))}}
        response (assets/objects-handler cfg request)
        cc       (get (::yres/headers response) "cache-control")]
    (t/is (string? cc))
    (t/is (clojure.string/starts-with? cc "max-age="))))

;; ----------------------------------------------------------------
;; Tests: middleware integration — session auth end-to-end
;; ----------------------------------------------------------------

(t/deftest session-auth-integration
  ;; Test the full session auth flow: create session → assign token →
  ;; authenticate request → access protected asset.
  (let [cfg     th/*system*
        manager (session/inmemory-manager)
        profile (th/create-profile* 1)

        ;; Create a session and generate a token
        session (->> (session/create-session manager {:profile-id (:id profile)
                                                      :user-agent "test-agent"})
                     (#'session/assign-token cfg))

        ;; Create a storage object in a non-public bucket
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        object  (create-storage-object! storage "profile" "profile data")

        ;; Simulate what the middleware chain does:
        ;; 1. mw/auth extracts token from cookie and sets ::http/auth-data
        ;; 2. session/authz reads ::http/auth-data and sets ::session/profile-id
        request {::http/auth-data {:type :cookie
                                   :token (:token session)
                                   :claims {:sid (:id session)
                                            :uid (:id profile)}
                                   :metadata {:ver 1}}
                 :path-params {:id (str (:id object))}}

        ;; Apply session/authz middleware
        handler (#'session/wrap-authz
                 (fn [req]
                   ;; This is where the actual handler would be called
                   ;; We verify that ::session/profile-id is set
                   req)
                 {::session/manager manager})
        result  (handler request)]

    ;; Verify the session auth set the profile-id
    (t/is (= (:id profile) (::session/profile-id result)))
    (t/is (some? (::session/session result)))))

;; ----------------------------------------------------------------
;; Tests: middleware integration — access token auth end-to-end
;; ----------------------------------------------------------------

(t/deftest access-token-auth-integration
  ;; Test the full access token flow: create token → authenticate
  ;; request → access protected asset.
  (let [profile (th/create-profile* 1)

        ;; Create an access token in the database
        atoken  (db/tx-run! th/*system*
                            access-token/create-access-token
                            (:id profile) "test-token" nil nil)

        ;; Create a storage object in a non-public bucket
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend))
        object  (create-storage-object! storage "profile" "profile data")

        ;; Simulate what the middleware chain does:
        ;; 1. mw/auth extracts token from Authorization header and sets ::http/auth-data
        ;; 2. actoken/authz reads ::http/auth-data and sets ::actoken/profile-id
        request {::http/auth-data {:type :token
                                   :token (:token atoken)
                                   :claims {:tid (:id atoken)}}
                 :path-params {:id (str (:id object))}}

        ;; Apply actoken/authz middleware
        handler (#'actoken/wrap-authz
                 (fn [req]
                   ;; Verify that ::actoken/profile-id is set
                   req)
                 th/*system*)
        result  (handler request)]

    ;; Verify the access token auth set the profile-id
    (t/is (= (:id profile) (::actoken/profile-id result)))))

;; ----------------------------------------------------------------
;; Tests: middleware chain — combined session + access token
;; ----------------------------------------------------------------

(t/deftest combined-middleware-chain
  ;; Test that both session/authz and actoken/authz work together
  ;; in the middleware chain, matching the assets route configuration.
  (let [cfg     th/*system*
        manager (session/inmemory-manager)
        profile (th/create-profile* 1)

        ;; Create a session
        session (->> (session/create-session manager {:profile-id (:id profile)
                                                      :user-agent "test-agent"})
                     (#'session/assign-token cfg))

        ;; Create an access token
        atoken  (db/tx-run! th/*system*
                            access-token/create-access-token
                            (:id profile) "test-token" nil nil)

        ;; Build the middleware chain like assets routes do:
        ;; session/authz → actoken/authz → handler
        inner-handler (fn [request] request)
        with-actoken  (#'actoken/wrap-authz inner-handler th/*system*)
        with-session  (#'session/wrap-authz with-actoken {::session/manager manager})]

    (t/testing "session cookie auth sets ::session/profile-id"
      (let [request  {::http/auth-data {:type :cookie
                                        :token (:token session)
                                        :claims {:sid (:id session)
                                                 :uid (:id profile)}
                                        :metadata {:ver 1}}}
            result   (with-session request)]
        (t/is (= (:id profile) (::session/profile-id result)))))

    (t/testing "access token auth sets ::actoken/profile-id"
      (let [request  {::http/auth-data {:type :token
                                        :token (:token atoken)
                                        :claims {:tid (:id atoken)}}}
            result   (with-session request)]
        (t/is (= (:id profile) (::actoken/profile-id result)))))

    (t/testing "no auth sets neither profile-id"
      (let [request  {}
            result   (with-session request)]
        (t/is (nil? (::session/profile-id result)))
        (t/is (nil? (::actoken/profile-id result)))))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — edge cases
;; ----------------------------------------------------------------

(t/deftest objects-handler-nil-profile-id-in-session
  ;; When session auth is present but profile-id is nil (e.g. invalid session),
  ;; non-public objects should still be denied.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        object   (create-storage-object! storage "profile" "data")
        request  {:path-params {:id (str (:id object))}
                  ::session/profile-id nil}
        response (assets/objects-handler cfg request)]
    (t/is (= 401 (::yres/status response)))))

(t/deftest objects-handler-nil-profile-id-in-access-token
  ;; When access token auth is present but profile-id is nil,
  ;; non-public objects should still be denied.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        object   (create-storage-object! storage "profile" "data")
        request  {:path-params {:id (str (:id object))}
                  ::actoken/profile-id nil}
        response (assets/objects-handler cfg request)]
    (t/is (= 401 (::yres/status response)))))

(t/deftest objects-handler-empty-request
  ;; A request with no path-params should raise a not-found error.
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        request  {}]
    (try
      (assets/objects-handler cfg request)
      (t/is false "should have thrown")
      (catch Exception e
        (t/is (= :not-found (:type (ex-data e))))))))

;; ----------------------------------------------------------------
;; Tests: objects-handler — expired objects
;; ----------------------------------------------------------------

;; ----------------------------------------------------------------
;; Tests: file-objects-handler — authz required (T2-N1-01)
;; ----------------------------------------------------------------

(t/deftest file-objects-handler-unauthenticated-returns-404
  ;; Unauthenticated requests to file-media assets must return 404
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id profile)})
        project  (th/create-project* 1 {:profile-id (:id profile)
                                        :team-id (:id team)})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:id project)})
        media-storage (create-storage-object! storage "file-media-object" "image data")
        media-obj (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id (:id media-storage)})
        request  {:path-params {:id (str (:id media-obj))}}
        response (assets/file-objects-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))

(t/deftest file-objects-handler-no-file-perms-returns-404
  ;; Authenticated user without file read permissions must get 404
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        owner    (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:profile-id (:id owner)
                                        :team-id (:id team)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        media-storage (create-storage-object! storage "file-media-object" "image data")
        media-obj (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id (:id media-storage)})
        stranger (th/create-profile* 2)
        request  {:path-params {:id (str (:id media-obj))}
                  ::session/profile-id (:id stranger)}
        response (assets/file-objects-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))

(t/deftest file-objects-handler-with-file-perms-succeeds
  ;; Authenticated user with file read permissions must get the object
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        owner    (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:profile-id (:id owner)
                                        :team-id (:id team)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        media-storage (create-storage-object! storage "file-media-object" "image data")
        media-obj (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id (:id media-storage)})
        request  {:path-params {:id (str (:id media-obj))}
                  ::session/profile-id (:id owner)}
        response (assets/file-objects-handler cfg request)]
    (t/is (= 204 (::yres/status response)))))

(t/deftest file-thumbnails-handler-unauthenticated-returns-404
  ;; Unauthenticated requests to file-thumbnail assets must return 404
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id profile)})
        project  (th/create-project* 1 {:profile-id (:id profile)
                                        :team-id (:id team)})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:id project)})
        media-storage (create-storage-object! storage "file-media-object" "image data")
        media-obj (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id (:id media-storage)})
        request  {:path-params {:id (str (:id media-obj))}}
        response (assets/file-thumbnails-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))

(t/deftest file-thumbnails-handler-with-file-perms-succeeds
  ;; Authenticated user with file read permissions must get the thumbnail
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        owner    (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:profile-id (:id owner)
                                        :team-id (:id team)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        thumb-storage (create-storage-object! storage "file-object-thumbnail" "thumb data")
        media-obj (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id (:id thumb-storage)})
        request  {:path-params {:id (str (:id media-obj))}
                  ::session/profile-id (:id owner)}
        response (assets/file-thumbnails-handler cfg request)]
    ;; Falls back to media-id since no thumbnail-id, but still serves
    (t/is (= 204 (::yres/status response)))))

(t/deftest file-objects-handler-non-existent-media-returns-404
  ;; Request for non-existent file-media-object returns 404
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        request  {:path-params {:id (str (uuid/next))}
                  ::session/profile-id (:id profile)}
        response (assets/file-objects-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))

(t/deftest file-objects-handler-nil-profile-id-returns-404
  ;; When profile-id is nil (invalid session), must return 404
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        team     (th/create-team* 1 {:profile-id (:id profile)})
        project  (th/create-project* 1 {:profile-id (:id profile)
                                        :team-id (:id team)})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:id project)})
        media-storage (create-storage-object! storage "file-media-object" "image data")
        media-obj (th/create-file-media-object* {:file-id (:id file)
                                                 :media-id (:id media-storage)})
        request  {:path-params {:id (str (:id media-obj))}
                  ::session/profile-id nil}
        response (assets/file-objects-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))

(t/deftest objects-handler-expired-object
  ;; Expired objects should return 404 (get-object filters them out).
  (let [storage  (-> (:app.storage/storage th/*system*)
                     (configure-storage-backend))
        cfg      (make-handler-cfg storage)
        profile  (th/create-profile* 1)
        object   (sto/put-object! storage {::sto/content (sto/content "expired")
                                           ::sto/expired-at (ct/now)
                                           :bucket "profile"
                                           :content-type "text/plain"})
        request  {:path-params {:id (str (:id object))}
                  ::session/profile-id (:id profile)}
        response (assets/objects-handler cfg request)]
    (t/is (= 404 (::yres/status response)))))
