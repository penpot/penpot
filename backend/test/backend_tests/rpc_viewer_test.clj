;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-viewer-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.viewer :as viewer]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.datafy :refer [datafy]]
   [clojure.string :as str]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private safe-library-envelope-keys
  "Top-level keys a trimmed library may expose next to `:data`. Anything
   else is design content leaking through the envelope (see F3)."
  #{:backend :comment-thread-seqn :created-at :features :has-media-trimmed
    :id :is-indirect :is-shared :migrations :modified-at :name :project-id
    :revn :synced-at :team-id :vern :version})

(defn- update-file!
  [& {:keys [profile-id file-id changes]}]
  (let [out (th/command! {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn 0
                          :vern 0
                          :changes changes})]
    (t/is (nil? (:error out)))
    (:result out)))

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
        (t/is (some #(= link-b-id (:id %)) share-links))))

    (t/testing "share-link viewer does not query sibling share-links"
      ;; Direct regression test for the predicate-pushdown invariant:
      ;; on the share-link path the bundle must resolve the caller's
      ;; row with a composite (id, file-id) single-row lookup and must
      ;; never run the full {:file-id} query that would load sibling
      ;; tokens into the backend process. The with-redefs spies only
      ;; record and delegate, following the instrumentation style used
      ;; elsewhere in this suite (e.g. auth-ldap-test).
      (let [share-queries (atom [])
            share-gets    (atom [])
            orig-query    @#'db/query
            orig-get*     @#'db/get*]
        (with-redefs [db/query (fn [conn table params & opts]
                                 (when (= :share-link table)
                                   (swap! share-queries conj params))
                                 (apply orig-query conn table params opts))
                      db/get*  (fn [conn table params & opts]
                                 (when (= :share-link table)
                                   (swap! share-gets conj params))
                                 (apply orig-get* conn table params opts))]
          (let [out    (th/command! {::th/type :get-view-only-bundle
                                     :share-id link-a-id
                                     :file-id (:id file)})
                result (:result out)]
            (t/is (nil? (:error out)))
            (t/is (= 1 (count (:share-links result))))
            (t/is (= link-a-id (:id (first (:share-links result)))))
            ;; No full-file sibling query ran on this path.
            (t/is (empty? @share-queries))
            ;; Only composite single-row lookups ran (the permission
            ;; check plus the bundle itself, same predicate in both).
            (t/is (seq @share-gets))
            (t/is (every? #(= {:id link-a-id :file-id (:id file)} %)
                          @share-gets))))))

    (t/testing "cross-file share-id replay fails closed"
      (let [other-file (th/create-file* 2 {:profile-id (:id owner)
                                           :project-id proj-id
                                           :is-shared false})
            out (th/command! {::th/type :get-view-only-bundle
                              :share-id link-a-id
                              :file-id (:id other-file)})
            error (:error out)
            error-data (ex-data error)]
        (t/is (th/ex-info? error))
        (t/is (= :not-found (:type error-data)))
        (t/is (= :object-not-found (:code error-data)))))))

(t/deftest trim-library-data-unit
  (let [lib-id  (uuid/random)
        comp-a  (uuid/random)
        comp-b  (uuid/random)
        comp-c  (uuid/random)
        page-id (uuid/random)

        mk-shape (fn [id comp] {:id id :type :rect :component-id comp :component-file lib-id})
        shape-a  (mk-shape (uuid/random) comp-a)

        primary {:pages [page-id]
                 :pages-index {page-id {:id page-id :objects {(:id shape-a) shape-a}}}
                 :components {}}

        lib     {:id lib-id
                 :synced-at "now"
                 :data {:id lib-id
                        :options {}
                        :pages [page-id]
                        :pages-index {page-id {:id page-id}}
                        :components {comp-a {:id comp-a :name "a" :objects {}}
                                     comp-b {:id comp-b :name "b" :objects {}}}}}]

    (t/testing "keeps referenced components and drops library pages"
      (let [used    (#'viewer/collect-used-library-components primary {lib-id lib})
            trimmed (#'viewer/trim-library-data used lib)]
        (t/is (= #{comp-a} (get used lib-id)))
        (t/is (= #{comp-a} (set (keys (get-in trimmed [:data :components])))))
        (t/is (= [] (get-in trimmed [:data :pages])))
        (t/is (= {} (get-in trimmed [:data :pages-index])))
        (t/is (= lib-id (:id trimmed)))
        (t/is (= "now" (:synced-at trimmed)))))

    (t/testing "no references keeps no components"
      (let [trimmed (#'viewer/trim-library-data {} lib)]
        (t/is (= {} (get-in trimmed [:data :components])))
        (t/is (= [] (get-in trimmed [:data :pages])))))

    (t/testing "follows nested component references through the main instance"
      ;; Stored components carry no `:objects`; nested references resolve
      ;; through the main-instance subtree on the library page.
      (let [main-used   (uuid/random)
            main-nested (uuid/random)
            child       (assoc (mk-shape (uuid/random) comp-c) :parent-id main-used)
            lib2        (assoc lib :data
                               {:id lib-id
                                :options {}
                                :pages [page-id]
                                :pages-index {page-id {:id page-id
                                                       :objects {main-used {:id main-used
                                                                            :type :frame
                                                                            :shapes [(:id child)]}
                                                                 (:id child) child
                                                                 main-nested {:id main-nested
                                                                              :type :frame}}}}
                                :components {comp-a {:id comp-a
                                                     :name "a"
                                                     :main-instance-id main-used
                                                     :main-instance-page page-id}
                                             comp-b {:id comp-b
                                                     :name "b"}
                                             comp-c {:id comp-c
                                                     :name "c"
                                                     :main-instance-id main-nested
                                                     :main-instance-page page-id}}})
            used        (#'viewer/collect-used-library-components primary {lib-id lib2})
            trimmed     (#'viewer/trim-library-data used lib2)]
        (t/is (= #{comp-a comp-c} (get used lib-id)))
        (t/is (= #{comp-a comp-c} (set (keys (get-in trimmed [:data :components])))))
        (t/is (not (contains? (get-in trimmed [:data :components]) comp-b)))))))

(t/deftest share-link-bundle-trims-linked-libraries
  (let [owner    (th/create-profile* 1 {:is-active true})
        stranger (th/create-profile* 2 {:is-active true})
        proj-id  (:default-project-id owner)

        lib      (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared true})

        ;; A second page that only exists inside the library; the main
        ;; file never references it, so no share link on the main file
        ;; should ever expose it.
        canary   (uuid/random)

        _        (th/command! {::th/type :update-file
                               ::rpc/profile-id (:id owner)
                               :id (:id lib)
                               :session-id (uuid/random)
                               :revn 0
                               :vern 0
                               :changes [{:type :add-page
                                          :id canary
                                          :page {:id canary
                                                 :name "Private canary"
                                                 :options {}
                                                 :objects {}}}]})

        file     (th/create-file* 2 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared false})

        _        (th/link-file-to-library* {:file-id (:id file)
                                            :library-id (:id lib)})

        slink    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{(get-in file [:data :pages 0])}
                               :who-comment "team"
                               :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "control: non-member cannot read the library directly"
      (let [out (th/command! {::th/type :get-file
                              ::rpc/profile-id (:id stranger)
                              :id (:id lib)
                              :components-v2 true})
            error-data (ex-data (:error out))]
        (t/is (th/ex-info? (:error out)))
        (t/is (= :not-found (:type error-data)))))

    (t/testing "anonymous bundle omits unreferenced library pages"
      (let [out       (th/command! {::th/type :get-view-only-bundle
                                    :share-id slink-id
                                    :file-id (:id file)})
            result    (:result out)
            lib-pages (into #{} (mapcat #(get-in % [:data :pages] []))
                            (:libraries result))]
        (t/is (nil? (:error out)))
        (t/is (not (contains? lib-pages canary)))
        (t/is (every? #(= #{:id :options :pages :pages-index :components}
                          (set (keys (:data %))))
                      (:libraries result)))
        (t/is (every? #(every? safe-library-envelope-keys
                               (keys (dissoc % :data)))
                      (:libraries result)))))))

(t/deftest share-link-bundle-library-edge-cases
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)

        add-canary (fn [file n]
                     (let [canary (uuid/random)]
                       (th/command! {::th/type :update-file
                                     ::rpc/profile-id (:id owner)
                                     :id (:id file)
                                     :session-id (uuid/random)
                                     :revn 0
                                     :vern 0
                                     :changes [{:type :add-page
                                                :id canary
                                                :page {:id canary
                                                       :name n
                                                       :options {}
                                                       :objects {}}}]})
                       canary))

        lib2    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared true})
        canary2 (add-canary lib2 "Private canary 2")

        lib1    (th/create-file* 2 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared true})
        canary1 (add-canary lib1 "Private canary 1")

        _       (th/link-file-to-library* {:file-id (:id lib1)
                                           :library-id (:id lib2)})

        file    (th/create-file* 3 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})

        _       (th/link-file-to-library* {:file-id (:id file)
                                           :library-id (:id lib1)})

        full    (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :pages #{(get-in file [:data :pages 0])}
                              :who-comment "team"
                              :who-inspect "all"})
        full-id (get-in full [:result :id])

        empty   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :pages #{}
                              :who-comment "team"
                              :who-inspect "team"})
        empty-id (get-in empty [:result :id])

        bundle  (fn [& {:as params}]
                  (th/command! (merge {::th/type :get-view-only-bundle
                                       :file-id (:id file)}
                                      params)))
        lib-pages (fn [result]
                    (into #{} (mapcat #(get-in % [:data :pages] []))
                          (:libraries result)))]

    (t/testing "indirect libraries are trimmed too"
      (let [out    (bundle :share-id full-id)
            result (:result out)
            pages  (lib-pages result)]
        (t/is (nil? (:error out)))
        (t/is (= 2 (count (:libraries result))))
        (t/is (not (contains? pages canary1)))
        (t/is (not (contains? pages canary2)))))

    (t/testing "empty-pages link exposes no library content"
      (let [out    (bundle :share-id empty-id)
            result (:result out)]
        (t/is (nil? (:error out)))
        (t/is (every? #(= {} (get-in % [:data :components])) (:libraries result)))
        (t/is (every? #(= [] (get-in % [:data :pages])) (:libraries result)))))

    (t/testing "member bundle keeps full libraries"
      (let [out    (bundle ::rpc/profile-id (:id owner))
            result (:result out)
            pages  (lib-pages result)]
        (t/is (nil? (:error out)))
        (t/is (contains? pages canary1))
        (t/is (contains? pages canary2))))

    (t/testing "file without libraries returns no libraries"
      (let [plain (th/create-file* 4 {:profile-id (:id owner)
                                      :project-id proj-id
                                      :is-shared false})
            link  (th/command! {::th/type :create-share-link
                                ::rpc/profile-id (:id owner)
                                :file-id (:id plain)
                                :pages #{(get-in plain [:data :pages 0])}
                                :who-comment "team"
                                :who-inspect "all"})
            out   (th/command! {::th/type :get-view-only-bundle
                                :share-id (get-in link [:result :id])
                                :file-id (:id plain)})]
        (t/is (nil? (:error out)))
        (t/is (= [] (:libraries (:result out))))))))

(t/deftest share-link-bundle-keeps-used-library-components
  (let [owner    (th/create-profile* 1 {:is-active true})
        proj-id  (:default-project-id owner)

        lib      (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared true})
        lib-page (first (get-in lib [:data :pages]))

        c-nested (uuid/random)
        c-used   (uuid/random)
        c-unused (uuid/random)
        n-main   (uuid/random)
        u-main   (uuid/random)
        u-child  (uuid/random)
        x-main   (uuid/random)
        inst     (uuid/random)

        frame    (fn [id parent frame-id extra]
                   (cts/setup-shape
                    (merge {:id id
                            :name "Board"
                            :frame-id frame-id
                            :parent-id parent
                            :type :frame}
                           extra)))

        ;; Nested component, referenced only through c-used.
        _        (update-file!
                  :profile-id (:id owner)
                  :file-id (:id lib)
                  :changes [{:type :add-obj
                             :page-id lib-page
                             :id n-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame n-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file (:id lib)
                                          :component-id c-nested})}
                            {:type :add-component
                             :path ""
                             :name "nested"
                             :main-instance-id n-main
                             :main-instance-page lib-page
                             :id c-nested
                             :anotation nil}])

        ;; Used component, instancing the nested one.
        _        (update-file!
                  :profile-id (:id owner)
                  :file-id (:id lib)
                  :changes [{:type :add-obj
                             :page-id lib-page
                             :id u-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame u-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file (:id lib)
                                          :component-id c-used})}
                            {:type :add-obj
                             :page-id lib-page
                             :id u-child
                             :parent-id u-main
                             :frame-id u-main
                             :components-v2 true
                             :obj (frame u-child u-main u-main
                                         {:main-instance false
                                          :component-root true
                                          :component-file (:id lib)
                                          :component-id c-nested})}
                            {:type :add-component
                             :path ""
                             :name "used"
                             :main-instance-id u-main
                             :main-instance-page lib-page
                             :id c-used
                             :anotation nil}])

        ;; Unreferenced component, never instanced anywhere.
        _        (update-file!
                  :profile-id (:id owner)
                  :file-id (:id lib)
                  :changes [{:type :add-obj
                             :page-id lib-page
                             :id x-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame x-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file (:id lib)
                                          :component-id c-unused})}
                            {:type :add-component
                             :path ""
                             :name "unused"
                             :main-instance-id x-main
                             :main-instance-page lib-page
                             :id c-unused
                             :anotation nil}])

        file     (th/create-file* 2 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared false})
        main-page (first (get-in file [:data :pages]))

        ;; Instance the used component on the main file.
        _        (update-file!
                  :profile-id (:id owner)
                  :file-id (:id file)
                  :changes [{:type :add-obj
                             :page-id main-page
                             :id inst
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame inst uuid/zero uuid/zero
                                         {:main-instance false
                                          :component-root true
                                          :component-file (:id lib)
                                          :component-id c-used})}])

        _        (th/link-file-to-library* {:file-id (:id file)
                                            :library-id (:id lib)})

        slink    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{main-page}
                               :who-comment "team"
                               :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "anonymous bundle keeps referenced components and drops the rest"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id (:id file)})
            result (:result out)
            libs   (:libraries result)]
        (t/is (nil? (:error out)))
        (t/is (= 1 (count libs)))
        (t/is (= #{c-used c-nested}
                 (set (keys (get-in (first libs) [:data :components])))))
        (t/is (= [] (get-in (first libs) [:data :pages])))))))

(t/deftest share-link-bundle-drops-components-from-disallowed-pages
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)

        lib     (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared true})
        lib-page (first (get-in lib [:data :pages]))

        comp    (uuid/random)
        main    (uuid/random)

        _       (update-file!
                 :profile-id (:id owner)
                 :file-id (:id lib)
                 :changes [{:type :add-obj
                            :page-id lib-page
                            :id main
                            :parent-id uuid/zero
                            :frame-id uuid/zero
                            :components-v2 true
                            :obj (cts/setup-shape
                                  {:id main
                                   :name "Board"
                                   :frame-id uuid/zero
                                   :parent-id uuid/zero
                                   :type :frame
                                   :main-instance true
                                   :component-root true
                                   :component-file (:id lib)
                                   :component-id comp})}
                           {:type :add-component
                            :path ""
                            :name "Board"
                            :main-instance-id main
                            :main-instance-page lib-page
                            :id comp
                            :anotation nil}])

        file    (th/create-file* 2 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)
        inst    (uuid/random)

        ;; Second page, outside the share link scope.
        _       (update-file!
                 :profile-id (:id owner)
                 :file-id (:id file)
                 :changes [{:type :add-page
                            :id page-b
                            :page {:id page-b
                                   :name "Hidden"
                                   :options {}
                                   :objects {}}}])

        ;; Instance the library component only on the disallowed page.
        _       (update-file!
                 :profile-id (:id owner)
                 :file-id (:id file)
                 :changes [{:type :add-obj
                            :page-id page-b
                            :id inst
                            :parent-id uuid/zero
                            :frame-id uuid/zero
                            :components-v2 true
                            :obj (cts/setup-shape
                                  {:id inst
                                   :name "Board"
                                   :frame-id uuid/zero
                                   :parent-id uuid/zero
                                   :type :frame
                                   :main-instance false
                                   :component-root true
                                   :component-file (:id lib)
                                   :component-id comp})}])

        _       (th/link-file-to-library* {:file-id (:id file)
                                           :library-id (:id lib)})

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})]

    (t/testing "references from disallowed pages seed nothing"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id (get-in slink [:result :id])
                                 :file-id (:id file)})
            result (:result out)
            libs   (:libraries result)]
        (t/is (nil? (:error out)))
        (t/is (= 1 (count libs)))
        (t/is (= {} (get-in (first libs) [:data :components])))
        (t/is (= [] (get-in (first libs) [:data :pages])))))))

(t/deftest share-link-bundle-keeps-cross-library-nested-components
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)

        lib2    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared true})
        lib2-page (first (get-in lib2 [:data :pages]))

        comp-b  (uuid/random)
        b-main  (uuid/random)

        _       (update-file!
                 :profile-id (:id owner)
                 :file-id (:id lib2)
                 :changes [{:type :add-obj
                            :page-id lib2-page
                            :id b-main
                            :parent-id uuid/zero
                            :frame-id uuid/zero
                            :components-v2 true
                            :obj (cts/setup-shape
                                  {:id b-main
                                   :name "Leaf"
                                   :frame-id uuid/zero
                                   :parent-id uuid/zero
                                   :type :frame
                                   :main-instance true
                                   :component-root true
                                   :component-file (:id lib2)
                                   :component-id comp-b})}
                           {:type :add-component
                            :path ""
                            :name "leaf"
                            :main-instance-id b-main
                            :main-instance-page lib2-page
                            :id comp-b
                            :anotation nil}])

        lib1    (th/create-file* 2 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared true})
        lib1-page (first (get-in lib1 [:data :pages]))

        _       (th/link-file-to-library* {:file-id (:id lib1)
                                           :library-id (:id lib2)})

        comp-a  (uuid/random)
        a-main  (uuid/random)
        a-child (uuid/random)

        ;; Component whose main instance embeds an instance from lib2.
        _       (update-file!
                 :profile-id (:id owner)
                 :file-id (:id lib1)
                 :changes [{:type :add-obj
                            :page-id lib1-page
                            :id a-main
                            :parent-id uuid/zero
                            :frame-id uuid/zero
                            :components-v2 true
                            :obj (cts/setup-shape
                                  {:id a-main
                                   :name "Wrapper"
                                   :frame-id uuid/zero
                                   :parent-id uuid/zero
                                   :type :frame
                                   :main-instance true
                                   :component-root true
                                   :component-file (:id lib1)
                                   :component-id comp-a})}
                           {:type :add-obj
                            :page-id lib1-page
                            :id a-child
                            :parent-id a-main
                            :frame-id a-main
                            :components-v2 true
                            :obj (cts/setup-shape
                                  {:id a-child
                                   :name "Leaf"
                                   :frame-id a-main
                                   :parent-id a-main
                                   :type :frame
                                   :main-instance false
                                   :component-root true
                                   :component-file (:id lib2)
                                   :component-id comp-b})}
                           {:type :add-component
                            :path ""
                            :name "wrapper"
                            :main-instance-id a-main
                            :main-instance-page lib1-page
                            :id comp-a
                            :anotation nil}])

        file    (th/create-file* 3 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        main-page (first (get-in file [:data :pages]))
        inst    (uuid/random)

        _       (update-file!
                 :profile-id (:id owner)
                 :file-id (:id file)
                 :changes [{:type :add-obj
                            :page-id main-page
                            :id inst
                            :parent-id uuid/zero
                            :frame-id uuid/zero
                            :components-v2 true
                            :obj (cts/setup-shape
                                  {:id inst
                                   :name "Wrapper"
                                   :frame-id uuid/zero
                                   :parent-id uuid/zero
                                   :type :frame
                                   :main-instance false
                                   :component-root true
                                   :component-file (:id lib1)
                                   :component-id comp-a})}])

        _       (th/link-file-to-library* {:file-id (:id file)
                                           :library-id (:id lib1)})

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :pages #{main-page}
                              :who-comment "team"
                              :who-inspect "all"})]

    (t/testing "nested references resolve across libraries"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id (get-in slink [:result :id])
                                 :file-id (:id file)})
            result (:result out)
            by-id  (into {} (map (juxt :id identity)) (:libraries result))]
        (t/is (nil? (:error out)))
        (t/is (= 2 (count (:libraries result))))
        (t/is (= #{comp-a}
                 (set (keys (get-in by-id [(:id lib1) :data :components])))))
        (t/is (= #{comp-b}
                 (set (keys (get-in by-id [(:id lib2) :data :components])))))
        (t/is (= [] (get-in by-id [(:id lib2) :data :pages])))))))

(t/deftest pages-carry-the-values-of-their-tokens
  (let [prof     (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id prof)
                                     :project-id (:default-project-id prof)})
        page-id  (get-in file [:data :pages 0])
        frame-id (uuid/next)
        rect-id  (uuid/next)
        other-id (uuid/next)]
    (update-file! :profile-id (:id prof)
                  :file-id (:id file)
                  :changes [{:type :add-obj
                             :page-id page-id
                             :id frame-id
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :obj (cts/setup-shape {:id frame-id :name "frame" :type :frame})}
                            {:type :add-obj
                             :page-id page-id
                             :id rect-id
                             :parent-id frame-id
                             :frame-id frame-id
                             :obj (-> (cts/setup-shape {:id rect-id :name "rect" :type :rect})
                                      (assoc :applied-tokens {:r1 "radius"}
                                             :token-values {:r1 4}))}
                            {:type :add-obj
                             :page-id page-id
                             :id other-id
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :obj (cts/setup-shape {:id other-id :name "other" :type :frame})}
                            {:type :set-tokens-resolved
                             :values {"radius" 12 "unused" 1}}])

    (t/testing "get-page tells the values of its tokens, and whether it is out of date"
      (let [{:keys [result error]} (th/command! {::th/type :get-page
                                                 ::rpc/profile-id (:id prof)
                                                 :file-id (:id file)
                                                 :page-id page-id
                                                 :object-id frame-id})]
        (t/is (nil? error))
        (t/is (= {"radius" 12} (:tokens-resolved result)))
        (t/is (true? (:tokens-out-of-date result)))
        (t/is (contains? (:objects result) uuid/zero)
              "an out of date page comes whole, to set it before keeping the object")))

    (t/testing "get-page checks only the boards that hold the objects"
      (let [{:keys [result error]} (th/command! {::th/type :get-page
                                                 ::rpc/profile-id (:id prof)
                                                 :file-id (:id file)
                                                 :page-id page-id
                                                 :object-id other-id})]
        (t/is (nil? error))
        (t/is (nil? (:tokens-out-of-date result)))
        (t/is (= #{other-id} (set (keys (:objects result)))))))

    (t/testing "the view only bundle carries the values of the tokens of its pages"
      (let [{:keys [result error]} (th/command! {::th/type :get-view-only-bundle
                                                 ::rpc/profile-id (:id prof)
                                                 :file-id (:id file)})]
        (t/is (nil? error))
        (t/is (= {"radius" 12} (:tokens-resolved result)))))))

(defn- token-changes
  "Changes that add the active set `set-id` with `tokens`, a map of token
  name to `[type value]`."
  [set-id tokens]
  (concat
   [{:type :set-token-set
     :id set-id
     :attrs (datafy (ctob/make-token-set :id set-id :name "base"))}]
   (for [[token-name [type value]] tokens]
     (let [token (ctob/make-token :id (uuid/next) :name token-name :type type :value value)]
       {:type :set-token
        :set-id set-id
        :token-id (:id token)
        :attrs (datafy token)}))
   [{:type :set-tokens-status :theme-ids #{} :set-ids #{set-id}}]))

(defn- rect-change
  [page-id rect-id applied-tokens]
  {:type :add-obj
   :page-id page-id
   :id rect-id
   :parent-id uuid/zero
   :frame-id uuid/zero
   :obj (-> (cts/setup-shape {:id rect-id :name "rect" :type :rect})
            (assoc :applied-tokens applied-tokens))})

(def ^:private tokens
  {"radius"  [:border-radius "{base}"]
   "base"    [:border-radius "12"]
   "shadow"  [:shadow [{:offset-x "{base}" :offset-y "2" :blur "4" :spread "0" :color "{brand}" :inset false}]]
   "brand"   [:color "#ff0000"]
   "unused"  [:border-radius "{base}"]})

(t/deftest pages-carry-the-tokens-they-use
  (let [prof    (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:default-project-id prof)})
        page-id (get-in file [:data :pages 0])
        set-id  (uuid/next)]
    (update-file! :profile-id (:id prof)
                  :file-id (:id file)
                  :changes (concat (token-changes set-id tokens)
                                   [(rect-change page-id (uuid/next) {:r1 "radius" :shadow "shadow"})]))

    (t/testing "the view only bundle carries the tokens its pages apply and the ones they reference"
      (let [{:keys [result error]} (th/command! {::th/type :get-view-only-bundle
                                                 ::rpc/profile-id (:id prof)
                                                 :file-id (:id file)})]
        (t/is (nil? error))
        (t/is (= #{"radius" "base" "shadow" "brand"} (set (keys (:tokens result)))))
        (t/is (= {:name "radius" :type :border-radius :value "{base}"}
                 (get-in result [:tokens "radius"])))))

    (t/testing "get-page carries them too"
      (let [{:keys [result error]} (th/command! {::th/type :get-page
                                                 ::rpc/profile-id (:id prof)
                                                 :file-id (:id file)
                                                 :page-id page-id})]
        (t/is (nil? error))
        (t/is (= #{"radius" "base" "shadow" "brand"} (set (keys (:tokens result)))))))

    (t/testing "the data of the thumbnail carries them too"
      (let [{:keys [result error]} (th/command! {::th/type :get-file-data-for-thumbnail
                                                 ::rpc/profile-id (:id prof)
                                                 :file-id (:id file)})]
        (t/is (nil? error))
        (t/is (= #{"radius" "base" "shadow" "brand"} (set (keys (get-in result [:page :tokens])))))))))

(t/deftest pages-carry-the-tokens-of-their-tokens-source
  (let [prof     (th/create-profile* 1 {:is-active true})
        library  (th/create-file* 1 {:profile-id (:id prof)
                                     :project-id (:default-project-id prof)
                                     :is-shared true})
        file     (th/create-file* 2 {:profile-id (:id prof)
                                     :project-id (:default-project-id prof)})
        page-id  (get-in file [:data :pages 0])
        set-id   (uuid/next)]
    (update-file! :profile-id (:id prof)
                  :file-id (:id library)
                  :changes (token-changes set-id {"radius" [:border-radius "{base}"]
                                                  "base"   [:border-radius "12"]}))
    (th/link-file-to-library* {:file-id (:id file) :library-id (:id library)})
    (update-file! :profile-id (:id prof)
                  :file-id (:id file)
                  :changes [{:type :set-tokens-source :file-id (:id file) :library-id (:id library)}
                            {:type :set-tokens-status :theme-ids #{} :set-ids #{set-id}}
                            (rect-change page-id (uuid/next) {:r1 "radius"})])

    (let [{:keys [result error]} (th/command! {::th/type :get-view-only-bundle
                                               ::rpc/profile-id (:id prof)
                                               :file-id (:id file)})]
      (t/is (nil? error))
      (t/is (= {"radius" {:name "radius" :type :border-radius :value "{base}"}
                "base"   {:name "base" :type :border-radius :value "12"}}
               (:tokens result))))))
(t/deftest view-only-bundle-does-not-expose-the-storage-pointer
  ;; Both the file and its libraries are moved to cold storage, so their file
  ;; maps carry the internal pointer unless the response strips it.
  (let [owner    (th/create-profile* 1 {:is-active true})
        proj-id  (:default-project-id owner)
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared false})
        lib      (th/create-file* 2 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared true})
        page-id  (get-in file [:data :pages 0])
        _        (th/link-file-to-library* {:file-id (:id file)
                                            :library-id (:id lib)})
        slink    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{page-id}
                               :who-comment "team"
                               :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (th/run-task! :offload-file-data {:file-id (:id file)})
    (th/run-task! :offload-file-data {:file-id (:id lib)})

    (let [out    (th/command! {::th/type :get-view-only-bundle
                               :share-id slink-id
                               :file-id (:id file)})
          result (:result out)]
      (t/is (nil? (:error out)))
      (t/is (seq (:libraries result)))
      (t/is (not (contains? (:file result) :storage-object-id)))
      (t/is (every? #(not (contains? % :storage-object-id))
                    (:libraries result))))))

(t/deftest share-link-bundle-omits-unreferenced-deleted-component
  ;; Regression test: a page-A-only share link received the primary
  ;; file's full :components map, leaking the stored shapes of a deleted
  ;; component whose main instance lived on the excluded page B.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)
        shape   (uuid/random)
        comp    (uuid/random)
        marker  "SIICA-SYNTHETIC-DELETED"

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Hidden"
                                                :options {}
                                                :objects {}}}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id shape
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id shape
                                                :name marker
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :rect})}
                                        {:type :add-component
                                         :path ""
                                         :name "retired"
                                         :main-instance-id shape
                                         :main-instance-page page-b
                                         :id comp
                                         :anotation nil}
                                        {:type :del-component
                                         :id comp}])

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "share-link viewer does not receive the deleted component"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)]
        (t/is (nil? (:error out)))
        (t/is (not (contains? (get-in result [:file :data :components]) comp)))
        (t/is (= {} (get-in result [:file :data :components])))
        (t/is (not (str/includes? (pr-str out) marker)))))

    (t/testing "the excluded page is still absent from the bundle"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)]
        (t/is (= [page-a] (get-in result [:file :data :pages])))
        (t/is (not (contains? (get-in result [:file :data :pages-index]) page-b)))))

    (t/testing "team member still receives the full components map"
      (let [out  (th/command! {::th/type :get-file
                               ::rpc/profile-id (:id owner)
                               :id file-id})
            comps (get-in out [:result :data :components])]
        (t/is (nil? (:error out)))
        (t/is (contains? comps comp))))

    (t/testing "team member bundle keeps the full primary components map"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 ::rpc/profile-id (:id owner)
                                 :file-id file-id})
            result (:result out)
            comps  (get-in result [:file :data :components])]
        (t/is (nil? (:error out)))
        ;; The membership bundle carries lazy values; resolve the
        ;; components pointer with the file's own loader before checking.
        (fdata/with-pointers th/*system* file-id
          (t/is (contains? comps comp)))))))

(t/deftest share-link-bundle-keeps-referenced-components-and-nested-refs
  ;; Companion to the regression test above: the trim must keep
  ;; everything an allowed page actually renders. Component X is
  ;; referenced from page A but its main instance lives on the excluded
  ;; page B; X's subtree contains an instance of Y. A trim that does not
  ;; follow the fixpoint drops Y and breaks rendering. W is unreferenced
  ;; anywhere and must not be kept.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)

        x-main  (uuid/random)
        y-main  (uuid/random)
        w-main  (uuid/random)
        x-child (uuid/random)
        inst    (uuid/random)
        comp-x  (uuid/random)
        comp-y  (uuid/random)
        comp-w  (uuid/random)

        mk-frame (fn [id parent-id frame-id]
                   (cts/setup-shape {:id id
                                     :name (str id)
                                     :frame-id frame-id
                                     :parent-id parent-id
                                     :type :frame}))

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Hidden"
                                                :options {}
                                                :objects {}}}
                                        ;; X's main instance on the excluded page,
                                        ;; containing an instance of Y.
                                        {:type :add-obj
                                         :page-id page-b
                                         :id x-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (assoc (mk-frame x-main uuid/zero uuid/zero)
                                                     :main-instance true
                                                     :component-root true
                                                     :component-file file-id
                                                     :component-id comp-x)}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id x-child
                                         :parent-id x-main
                                         :frame-id x-main
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id x-child
                                                :name "nested"
                                                :frame-id x-main
                                                :parent-id x-main
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp-y})}
                                        ;; Y's main instance on the allowed page.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id y-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (assoc (mk-frame y-main uuid/zero uuid/zero)
                                                     :main-instance true
                                                     :component-root true
                                                     :component-file file-id
                                                     :component-id comp-y)}
                                        ;; W's main instance on the excluded page;
                                        ;; referenced nowhere.
                                        {:type :add-obj
                                         :page-id page-b
                                         :id w-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (assoc (mk-frame w-main uuid/zero uuid/zero)
                                                     :main-instance true
                                                     :component-root true
                                                     :component-file file-id
                                                     :component-id comp-w)}
                                        {:type :add-component
                                         :path ""
                                         :name "wrapper"
                                         :main-instance-id x-main
                                         :main-instance-page page-b
                                         :id comp-x
                                         :anotation nil}
                                        {:type :add-component
                                         :path ""
                                         :name "leaf"
                                         :main-instance-id y-main
                                         :main-instance-page page-a
                                         :id comp-y
                                         :anotation nil}
                                        {:type :add-component
                                         :path ""
                                         :name "unreferenced"
                                         :main-instance-id w-main
                                         :main-instance-page page-b
                                         :id comp-w
                                         :anotation nil}
                                        ;; A-page instance of X: the only reference
                                        ;; reachable from the allowed pages.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id inst
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id inst
                                                :name "instance"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp-x})}])

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "bundle keeps X (referenced from A) and Y (nested ref reached from X)"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)
            comps  (get-in result [:file :data :components])]
        (t/is (nil? (:error out)))
        (t/is (contains? comps comp-x))
        (t/is (contains? comps comp-y))
        (t/is (not (contains? comps comp-w)))))))

(t/deftest share-link-fragment-scope-blocks-components-pointer
  ;; Regression test: the share-link scope check for get-file-fragment
  ;; let the file's components fragment through because
  ;; get-used-pointer-ids collects top-level pointers indiscriminately.
  (let [owner   (th/create-profile* 1 {:is-active true})
        owner-id (:id owner)
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id owner-id
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)
        marker  "SIICA-SYNTHETIC-FRAGMENT"
        shape   (uuid/random)
        comp    (uuid/random)

        _       (update-file! :profile-id owner-id
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Excluded"
                                                :options {}
                                                :objects {}}}
                                        {:type :add-obj
                                         :page-id page-a
                                         :id shape
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id shape
                                                :name marker
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :rect})}
                                        {:type :add-component
                                         :path ""
                                         :name "secret"
                                         :main-instance-id shape
                                         :main-instance-page page-a
                                         :id comp
                                         :anotation nil}
                                        {:type :del-component
                                         :id comp}])

        ;; Enable pointer-map on the file, the way a migration would:
        ;; wrap the pages and components in pointers and persist them.
        _       (db/tx-run! th/*system*
                            (fn [cfg]
                              (fdata/with-tracked-pointers cfg file-id
                                (bfc/update-file! cfg
                                                  (-> (bfc/get-file cfg file-id)
                                                      (fdata/enable-pointer-map))))))

        comp-ptr-id (db/tx-run! th/*system*
                                (fn [cfg]
                                  (pmap/get-id (:components (:data (bfc/get-file cfg file-id))))))
        page-ptr-id (db/tx-run! th/*system*
                                (fn [cfg]
                                  (pmap/get-id (get (:pages-index (:data (bfc/get-file cfg file-id)))
                                                    page-a))))
        page-b-ptr-id (db/tx-run! th/*system*
                                  (fn [cfg]
                                    (pmap/get-id (get (:pages-index (:data (bfc/get-file cfg file-id)))
                                                      page-b))))

        stranger (th/create-profile* 2 {:is-active true})

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id owner-id
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "stranger with a page-A-only share link cannot read the components fragment"
      (let [out  (th/command! {::th/type :get-file-fragment
                               ::rpc/profile-id (:id stranger)
                               :share-id slink-id
                               :file-id file-id
                               :fragment-id comp-ptr-id})
            err  (:error out)
            edata (ex-data err)]
        (t/is (th/ex-info? err))
        (t/is (= :not-found (:type edata)))))

    (t/testing "the fragment of a page outside the link is denied"
      (let [out   (th/command! {::th/type :get-file-fragment
                                ::rpc/profile-id (:id stranger)
                                :share-id slink-id
                                :file-id file-id
                                :fragment-id page-b-ptr-id})
            err   (:error out)
            edata (ex-data err)]
        (t/is (th/ex-info? err))
        (t/is (= :not-found (:type edata)))))

    (t/testing "the page fragment of an allowed page remains available"
      (let [out (th/command! {::th/type :get-file-fragment
                              ::rpc/profile-id (:id stranger)
                              :share-id slink-id
                              :file-id file-id
                              :fragment-id page-ptr-id})]
        (t/is (nil? (:error out)))
        (t/is (= page-ptr-id (get-in out [:result :id])))))))

(t/deftest share-link-bundle-omits-unreferenced-deleted-component-when-pointer-mapped
  ;; Same defect as the regression test above, on a pointer-mapped
  ;; file: the trim emits the referenced subset inline so the served
  ;; bundle never carries (nor references) the full components map.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a    (first (get-in file [:data :pages]))
        page-b    (uuid/random)
        shape     (uuid/random)
        comp      (uuid/random)
        live-main (uuid/random)
        live-inst (uuid/random)
        comp-live (uuid/random)
        marker    "SIICA-SYNTHETIC-DELETED-PM"

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Hidden"
                                                :options {}
                                                :objects {}}}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id shape
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id shape
                                                :name marker
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :rect})}
                                        {:type :add-component
                                         :path ""
                                         :name "retired"
                                         :main-instance-id shape
                                         :main-instance-page page-b
                                         :id comp
                                         :anotation nil}
                                        {:type :del-component
                                         :id comp}
                                        ;; A live component referenced from
                                        ;; the allowed page must still reach
                                        ;; the bundle inline.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id live-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id live-main
                                                :name "live"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :main-instance true
                                                :component-root true
                                                :component-file file-id
                                                :component-id comp-live})}
                                        {:type :add-component
                                         :path ""
                                         :name "live"
                                         :main-instance-id live-main
                                         :main-instance-page page-a
                                         :id comp-live
                                         :anotation nil}
                                        {:type :add-obj
                                         :page-id page-a
                                         :id live-inst
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id live-inst
                                                :name "live-instance"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp-live})}])

        _       (db/tx-run! th/*system*
                            (fn [cfg]
                              (fdata/with-tracked-pointers cfg file-id
                                (bfc/update-file! cfg
                                                  (-> (bfc/get-file cfg file-id)
                                                      (fdata/enable-pointer-map))))))

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "bundle emits components inline as a plain map without the deleted component"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)
            comps  (get-in result [:file :data :components])]
        (t/is (nil? (:error out)))
        (t/is (not (pmap/pointer-map? comps)))
        (t/is (not (contains? comps comp)))
        (t/is (not (str/includes? (pr-str out) marker)))
        (t/is (contains? comps comp-live))))))

(t/deftest share-link-bundle-omits-referenced-deleted-component
  ;; The share-link bundle never carries a component marked as deleted,
  ;; even when the allowed page instances it: its stored shapes may
  ;; hold content of excluded pages. Instances render from the flat
  ;; page graph; the bundle needs no deleted components at all.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)

        main    (uuid/random)
        inst    (uuid/random)
        comp    (uuid/random)
        marker  "SIICA-SYNTHETIC-REFERENCED"

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Hidden"
                                                :options {}
                                                :objects {}}}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id main
                                                :name marker
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :main-instance true
                                                :component-root true
                                                :component-file file-id
                                                :component-id comp})}
                                        {:type :add-component
                                         :path ""
                                         :name "retired"
                                         :main-instance-id main
                                         :main-instance-page page-b
                                         :id comp
                                         :anotation nil}
                                        {:type :del-component
                                         :id comp}
                                        ;; An instance of the deleted
                                        ;; component on the allowed page.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id inst
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id inst
                                                :name "instance"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp})}])

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "the share bundle omits the referenced deleted component"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)
            comps  (get-in result [:file :data :components])]
        (t/is (nil? (:error out)))
        (t/is (not (contains? comps comp)))
        (t/is (not (str/includes? (pr-str out) marker)))))))

(t/deftest share-link-bundle-keeps-mutually-referencing-components
  ;; Components may reference each other (X contains an instance of Y
  ;; and Y an instance of X). The reachability fixpoint must terminate
  ;; and both components must reach the bundle.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)

        x-main  (uuid/random)
        x-child (uuid/random)
        y-main  (uuid/random)
        y-child (uuid/random)
        inst    (uuid/random)
        comp-x  (uuid/random)
        comp-y  (uuid/random)

        mk-frame (fn [id parent-id frame-id extra]
                   (cts/setup-shape (merge {:id id
                                            :name (str id)
                                            :frame-id frame-id
                                            :parent-id parent-id
                                            :type :frame}
                                           extra)))

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Hidden"
                                                :options {}
                                                :objects {}}}
                                        ;; X on the excluded page, containing Y.
                                        {:type :add-obj
                                         :page-id page-b
                                         :id x-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (mk-frame x-main uuid/zero uuid/zero
                                                        {:main-instance true
                                                         :component-root true
                                                         :component-file file-id
                                                         :component-id comp-x})}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id x-child
                                         :parent-id x-main
                                         :frame-id x-main
                                         :components-v2 true
                                         :obj (mk-frame x-child x-main x-main
                                                        {:component-file file-id
                                                         :component-id comp-y})}
                                        ;; Y on the allowed page, containing X.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id y-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (mk-frame y-main uuid/zero uuid/zero
                                                        {:main-instance true
                                                         :component-root true
                                                         :component-file file-id
                                                         :component-id comp-y})}
                                        {:type :add-obj
                                         :page-id page-a
                                         :id y-child
                                         :parent-id y-main
                                         :frame-id y-main
                                         :components-v2 true
                                         :obj (mk-frame y-child y-main y-main
                                                        {:component-file file-id
                                                         :component-id comp-x})}
                                        {:type :add-component
                                         :path ""
                                         :name "cross-a"
                                         :main-instance-id x-main
                                         :main-instance-page page-b
                                         :id comp-x
                                         :anotation nil}
                                        {:type :add-component
                                         :path ""
                                         :name "cross-b"
                                         :main-instance-id y-main
                                         :main-instance-page page-a
                                         :id comp-y
                                         :anotation nil}
                                        ;; An instance of X on the allowed page.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id inst
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (mk-frame inst uuid/zero uuid/zero
                                                        {:component-file file-id
                                                         :component-id comp-x})}])

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "bundle terminates and keeps both mutually referencing components"
      (let [out   (th/command! {::th/type :get-view-only-bundle
                                :share-id slink-id
                                :file-id file-id})
            result (:result out)
            comps  (get-in result [:file :data :components])]
        (t/is (nil? (:error out)))
        (t/is (contains? comps comp-x))
        (t/is (contains? comps comp-y))))))

(t/deftest share-link-bundle-does-not-expand-through-deleted-component
  ;; A deleted component stores the shapes of its subtree. A live
  ;; component referenced only from within those stored shapes is out
  ;; of scope: the reachability walk does not expand through deleted
  ;; components, so it stays out of the share-link bundle.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)

        e-main  (uuid/random)
        main    (uuid/random)
        nested  (uuid/random)
        inst    (uuid/random)
        comp-d  (uuid/random)
        comp-e  (uuid/random)

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Hidden"
                                                :options {}
                                                :objects {}}}
                                        ;; E: live component, main instance on
                                        ;; the excluded page; referenced only
                                        ;; inside deleted D's stored shapes.
                                        {:type :add-obj
                                         :page-id page-b
                                         :id e-main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id e-main
                                                :name "live-leaf"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :main-instance true
                                                :component-root true
                                                :component-file file-id
                                                :component-id comp-e})}
                                        ;; D: deleted component with an
                                        ;; instance of E inside.
                                        {:type :add-obj
                                         :page-id page-b
                                         :id main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id main
                                                :name "retired"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :main-instance true
                                                :component-root true
                                                :component-file file-id
                                                :component-id comp-d})}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id nested
                                         :parent-id main
                                         :frame-id main
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id nested
                                                :name "leaf-instance"
                                                :frame-id main
                                                :parent-id main
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp-e})}
                                        {:type :add-component
                                         :path ""
                                         :name "retired"
                                         :main-instance-id main
                                         :main-instance-page page-b
                                         :id comp-d
                                         :anotation nil}
                                        {:type :add-component
                                         :path ""
                                         :name "leaf"
                                         :main-instance-id e-main
                                         :main-instance-page page-b
                                         :id comp-e
                                         :anotation nil}
                                        {:type :del-component
                                         :id comp-d}
                                        ;; An instance of D on the allowed page.
                                        {:type :add-obj
                                         :page-id page-a
                                         :id inst
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id inst
                                                :name "instance"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp-d})}])

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "live E, referenced only through deleted D, stays out"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)
            comps  (get-in result [:file :data :components])]
        (t/is (nil? (:error out)))
        (t/is (not (contains? comps comp-d)))
        (t/is (not (contains? comps comp-e)))))))

(t/deftest share-link-bundle-drops-library-components-of-deleted-primary-component
  ;; A deleted primary component keeps its main-instance subtree in
  ;; `:objects`. A library component instanced only inside those stored
  ;; shapes is out of scope: the library trim walks the data the bundle
  ;; emits, whose `:components` map no longer carries deleted components.
  (let [owner    (th/create-profile* 1 {:is-active true})
        proj-id  (:default-project-id owner)

        lib      (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared true})
        lib-id   (:id lib)
        lib-page (first (get-in lib [:data :pages]))

        l-control (uuid/random)
        l-live    (uuid/random)
        l-retired (uuid/random)
        lc-main   (uuid/random)
        ll-main   (uuid/random)
        lr-main   (uuid/random)
        marker    "SIICA-LIB-RETIRED-REF"

        frame    (fn [id parent frame-id extra]
                   (cts/setup-shape
                    (merge {:id id
                            :name "Board"
                            :frame-id frame-id
                            :parent-id parent
                            :type :frame}
                           extra)))

        _        (update-file!
                  :profile-id (:id owner)
                  :file-id lib-id
                  :changes [{:type :add-obj
                             :page-id lib-page
                             :id lc-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame lc-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file lib-id
                                          :component-id l-control})}
                            {:type :add-obj
                             :page-id lib-page
                             :id ll-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame ll-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file lib-id
                                          :component-id l-live})}
                            {:type :add-obj
                             :page-id lib-page
                             :id lr-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame lr-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file lib-id
                                          :component-id l-retired})}
                            {:type :add-component
                             :path ""
                             :name "control"
                             :main-instance-id lc-main
                             :main-instance-page lib-page
                             :id l-control
                             :anotation nil}
                            {:type :add-component
                             :path ""
                             :name "live"
                             :main-instance-id ll-main
                             :main-instance-page lib-page
                             :id l-live
                             :anotation nil}
                            {:type :add-component
                             :path ""
                             :name marker
                             :main-instance-id lr-main
                             :main-instance-page lib-page
                             :id l-retired
                             :anotation nil}
                            ;; A retired library component keeps its
                            ;; stored shapes; no link may receive them.
                            {:type :del-component
                             :id l-retired}])

        file     (th/create-file* 2 {:profile-id (:id owner)
                                     :project-id proj-id
                                     :is-shared false})
        file-id  (:id file)
        page-a   (first (get-in file [:data :pages]))
        page-b   (uuid/random)

        c-deleted (uuid/random)
        d-main    (uuid/random)
        d-live    (uuid/random)
        d-retired (uuid/random)
        inst      (uuid/random)

        _        (update-file!
                  :profile-id (:id owner)
                  :file-id file-id
                  :changes [{:type :add-page
                             :id page-b
                             :page {:id page-b
                                    :name "Hidden"
                                    :options {}
                                    :objects {}}}
                            ;; The deleted component's stored subtree
                            ;; instances both library components.
                            {:type :add-obj
                             :page-id page-b
                             :id d-main
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame d-main uuid/zero uuid/zero
                                         {:main-instance true
                                          :component-root true
                                          :component-file file-id
                                          :component-id c-deleted})}
                            {:type :add-obj
                             :page-id page-b
                             :id d-live
                             :parent-id d-main
                             :frame-id d-main
                             :components-v2 true
                             :obj (frame d-live d-main d-main
                                         {:component-root true
                                          :component-file lib-id
                                          :component-id l-live})}
                            {:type :add-obj
                             :page-id page-b
                             :id d-retired
                             :parent-id d-main
                             :frame-id d-main
                             :components-v2 true
                             :obj (frame d-retired d-main d-main
                                         {:component-root true
                                          :component-file lib-id
                                          :component-id l-retired})}
                            {:type :add-component
                             :path ""
                             :name "retired"
                             :main-instance-id d-main
                             :main-instance-page page-b
                             :id c-deleted
                             :anotation nil}
                            {:type :del-component
                             :id c-deleted}
                            ;; The only library reference the link grants.
                            {:type :add-obj
                             :page-id page-a
                             :id inst
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (frame inst uuid/zero uuid/zero
                                         {:component-root true
                                          :component-file lib-id
                                          :component-id l-control})}])

        _        (th/link-file-to-library* {:file-id file-id
                                            :library-id lib-id})

        slink    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id file-id
                               :pages #{page-a}
                               :who-comment "team"
                               :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "the trim keeps only the library component the link references"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)
            libs   (:libraries result)
            comps  (get-in (first libs) [:data :components])]
        (t/is (nil? (:error out)))
        (t/is (= 1 (count libs)))
        (t/is (= #{l-control} (set (keys comps))))
        (t/is (not (contains? comps l-live)))
        (t/is (not (contains? comps l-retired)))
        (t/is (not (str/includes? (pr-str out) marker)))))))

(t/deftest share-link-bundle-full-scope-link-still-lists-pages
  ;; A link granting every page is the common "public prototype" case:
  ;; pages are listed, instances render, and even there a deleted
  ;; component does not travel.
  (let [owner   (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        page-b  (uuid/random)
        main    (uuid/random)
        inst    (uuid/random)
        comp    (uuid/random)

        _       (update-file! :profile-id (:id owner)
                              :file-id file-id
                              :changes [{:type :add-page
                                         :id page-b
                                         :page {:id page-b
                                                :name "Second"
                                                :options {}
                                                :objects {}}}
                                        {:type :add-obj
                                         :page-id page-b
                                         :id main
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id main
                                                :name "retired"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :main-instance true
                                                :component-root true
                                                :component-file file-id
                                                :component-id comp})}
                                        {:type :add-component
                                         :path ""
                                         :name "retired"
                                         :main-instance-id main
                                         :main-instance-page page-b
                                         :id comp
                                         :anotation nil}
                                        {:type :del-component
                                         :id comp}
                                        {:type :add-obj
                                         :page-id page-a
                                         :id inst
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id inst
                                                :name "instance"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :component-file file-id
                                                :component-id comp})}])

        slink   (th/command! {::th/type :create-share-link
                              ::rpc/profile-id (:id owner)
                              :file-id file-id
                              :pages #{page-a page-b}
                              :who-comment "team"
                              :who-inspect "all"})
        slink-id (get-in slink [:result :id])]

    (t/testing "full-scope link lists both pages; the deleted component does not travel"
      (let [out    (th/command! {::th/type :get-view-only-bundle
                                 :share-id slink-id
                                 :file-id file-id})
            result (:result out)
            data   (:file result)]
        (t/is (nil? (:error out)))
        (t/is (= #{page-a page-b} (set (get-in data [:data :pages]))))
        (t/is (contains? (get-in data [:data :pages-index page-a :objects]) inst))
        (t/is (not (contains? (get-in data [:data :components]) comp)))))))

(t/deftest membership-bundle-with-pointer-map-keeps-components-fragment
  ;; The share-link restrictions must not leak into the membership path:
  ;; a member still receives the components pointer and can resolve it.
  (let [owner   (th/create-profile* 1 {:is-active true})
        owner-id (:id owner)
        proj-id (:default-project-id owner)
        file    (th/create-file* 1 {:profile-id owner-id
                                    :project-id proj-id
                                    :is-shared false})
        file-id (:id file)
        page-a  (first (get-in file [:data :pages]))
        shape   (uuid/random)
        comp    (uuid/random)

        _       (update-file! :profile-id owner-id
                              :file-id file-id
                              :changes [{:type :add-obj
                                         :page-id page-a
                                         :id shape
                                         :parent-id uuid/zero
                                         :frame-id uuid/zero
                                         :components-v2 true
                                         :obj (cts/setup-shape
                                               {:id shape
                                                :name "live"
                                                :frame-id uuid/zero
                                                :parent-id uuid/zero
                                                :type :frame
                                                :main-instance true
                                                :component-root true
                                                :component-file file-id
                                                :component-id comp})}
                                        {:type :add-component
                                         :path ""
                                         :name "live"
                                         :main-instance-id shape
                                         :main-instance-page page-a
                                         :id comp
                                         :anotation nil}])

        _       (db/tx-run! th/*system*
                            (fn [cfg]
                              (fdata/with-tracked-pointers cfg file-id
                                (bfc/update-file! cfg
                                                  (-> (bfc/get-file cfg file-id)
                                                      (fdata/enable-pointer-map))))))

        comp-ptr-id (db/tx-run! th/*system*
                                (fn [cfg]
                                  (pmap/get-id (:components (:data (bfc/get-file cfg file-id))))))]

    (t/testing "membership bundle carries the components pointer"
      (let [out   (th/command! {::th/type :get-view-only-bundle
                                ::rpc/profile-id owner-id
                                :file-id file-id})
            comps (get-in out [:result :file :data :components])]
        (t/is (nil? (:error out)))
        (t/is (pmap/pointer-map? comps))))

    (t/testing "a member can resolve the components fragment"
      (let [out (th/command! {::th/type :get-file-fragment
                              ::rpc/profile-id owner-id
                              :file-id file-id
                              :fragment-id comp-ptr-id})]
        (t/is (nil? (:error out)))
        (t/is (= comp-ptr-id (get-in out [:result :id])))))))

(t/deftest same-file-component-ref-legacy-shapes
  ;; Legacy files omit `:component-file` on same-file references; the
  ;; shared predicate must treat that as a same-file reference and still
  ;; reject library shapes and plain shapes.
  (let [file-id  (uuid/random)
        other-id (uuid/random)
        legacy   {:component-id (uuid/random)}
        own      {:component-id (uuid/random) :component-file file-id}
        library  {:component-id (uuid/random) :component-file other-id}
        plain    {:name "not-a-component"}]

    (t/is (true? (#'viewer/same-file-component-ref? file-id legacy)))
    (t/is (true? (#'viewer/same-file-component-ref? file-id own)))
    (t/is (false? (#'viewer/same-file-component-ref? file-id library)))
    (t/is (false? (#'viewer/same-file-component-ref? file-id plain)))))
