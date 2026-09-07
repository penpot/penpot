;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-viewer-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.viewer :as viewer]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest obfuscate-email-happy-path
  (t/is (= "a****@****.com" (viewer/obfuscate-email "alice@example.com")))
  (t/is (= "a****@****.example.com" (viewer/obfuscate-email "alice@sub.example.com")))
  (t/is (= "****@****.com" (viewer/obfuscate-email "bob@bar.com"))))

(t/deftest obfuscate-email-handles-domain-without-dot
  ;; `localhost`-style domains have no `.`; the previous implementation produced
  ;; a dangling-dot output like "a****@****." — now the trailing `.` is only
  ;; emitted when there actually is a TLD segment to append.
  (t/is (= "a****@****" (viewer/obfuscate-email "alice@localhost")))
  (t/is (= "****@****" (viewer/obfuscate-email "x@y"))))

(t/deftest obfuscate-email-handles-malformed-input
  ;; These shapes must not throw — `obfuscate-email` runs while building the
  ;; view-only bundle for share-link viewers and an NPE here aborts the whole
  ;; RPC response. The previous implementation called `clojure.string/split`
  ;; on `nil` for the `no-@` case, raising NullPointerException.
  (t/is (= "****@****" (viewer/obfuscate-email nil)))
  (t/is (= "****@****" (viewer/obfuscate-email "")))
  (t/is (= "r***@****" (viewer/obfuscate-email "root")))       ; no `@`, count > 3
  (t/is (= "****@****" (viewer/obfuscate-email "bob"))))       ; no `@`, count <= 3

(t/deftest retrieve-bundle
  (let [prof     (th/create-profile* 1 {:is-active true})
        prof2    (th/create-profile* 2 {:is-active true})
        team-id  (:default-team-id prof)
        proj-id  (:default-project-id prof)

        file     (th/create-file* 1 {:profile-id (:id prof)
                                     :project-id proj-id
                                     :is-shared false})
        share-id (atom nil)]

    (t/testing "authenticated with page-id"
      (let [data {::th/type :get-view-only-bundle
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}

            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (contains? result :share-links))
          (t/is (contains? result :permissions))
          (t/is (contains? result :libraries))
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "generate share token"
      (let [data {::th/type :create-share-link
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :pages #{(get-in file [:data :pages 0])}
                  :who-comment "team"
                  :who-inspect "all"}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (uuid? (:id result)))
          (reset! share-id (:id result)))))

    (t/testing "not authenticated with page-id"
      (let [data {::th/type :get-view-only-bundle
                  ::rpc/profile-id (:id prof2)
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (let [error      (:error out)
              error-data (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type error-data) :not-found))
          (t/is (= (:code error-data) :object-not-found)))))

    (t/testing "authenticated with token & profile"
      (let [data {::th/type :get-view-only-bundle
                  ::rpc/profile-id (:id prof2)
                  :share-id @share-id
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))

        (let [result (:result out)]
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))

    (t/testing "authenticated with token"
      (let [data {::th/type :get-view-only-bundle
                  :share-id @share-id
                  :file-id (:id file)
                  :page-id (get-in file [:data :pages 0])
                  :components-v2 true}
            out  (th/command! data)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (contains? result :file))
          (t/is (contains? result :project)))))))

(t/deftest share-link-token-disclosure
  (let [owner  (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)

        file   (th/create-file* 1 {:profile-id (:id owner)
                                   :project-id proj-id
                                   :is-shared false})

        page-a (get-in file [:data :pages 0])
        page-b (uuid/random)

        ;; Add a second page to the file
        _      (th/command! {::th/type :update-file
                             ::rpc/profile-id (:id owner)
                             :id (:id file)
                             :session-id (uuid/random)
                             :revn 0
                             :vern 0
                             :changes [{:type :add-page
                                        :id page-b
                                        :page {:id page-b
                                               :name "Page B"
                                               :options {}
                                               :objects {}}}]})

        ;; Create Link A: restrictive (no pages, team-only comments/inspect)
        link-a (th/command! {::th/type :create-share-link
                             ::rpc/profile-id (:id owner)
                             :file-id (:id file)
                             :pages #{}
                             :who-comment "team"
                             :who-inspect "team"})
        link-a-id (get-in link-a [:result :id])

        ;; Create Link B: permissive (all pages, all can comment/inspect)
        link-b (th/command! {::th/type :create-share-link
                             ::rpc/profile-id (:id owner)
                             :file-id (:id file)
                             :pages #{page-a page-b}
                             :who-comment "all"
                             :who-inspect "all"})
        link-b-id (get-in link-b [:result :id])]

    (t/testing "restrictive share-link holder cannot see other share-link tokens"
      (let [out (th/command! {::th/type :get-view-only-bundle
                              :share-id link-a-id
                              :file-id (:id file)})
            err (:error out)
            result (:result out)
            share-links (:share-links result)]

        ;; Should not error
        (t/is (nil? err))

        ;; Should only see the share-link used for authentication
        (t/is (= 1 (count share-links)))
        (t/is (= link-a-id (:id (first share-links))))

        ;; Should NOT see Link B's token
        (t/is (not (some #(= link-b-id (:id %)) share-links)))))

    (t/testing "team member still sees all share-links"
      (let [out (th/command! {::th/type :get-view-only-bundle
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)})
            err (:error out)
            result (:result out)
            share-links (:share-links result)]

        ;; Should not error
        (t/is (nil? err))

        ;; Team member should see both share-links
        (t/is (= 2 (count share-links)))
        (t/is (some #(= link-a-id (:id %)) share-links))
        (t/is (some #(= link-b-id (:id %)) share-links))))))
