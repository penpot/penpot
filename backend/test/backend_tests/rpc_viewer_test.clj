;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-viewer-test
  (:require
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.viewer :as viewer]
   [backend-tests.helpers :as th]
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
        (t/is (some #(= link-b-id (:id %)) share-links))))))

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
