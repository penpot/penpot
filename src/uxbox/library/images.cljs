;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.library.images)

(def ^:static +collections+
  [{:name "Generic 1"
    :id 1
    :builtin true
    :images [{:id 1
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1455098934982-64c622c5e066?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1400"
              :thumbnail "https://images.unsplash.com/photo-1455098934982-64c622c5e066?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1400"}
             {:id 2
              :name "image_name_long_name.jpg"
              :url "https://images.unsplash.com/photo-1422393462206-207b0fbd8d6b?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1925"
              :thumbnail "https://images.unsplash.com/photo-1422393462206-207b0fbd8d6b?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1925"}
             {:id 3
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014"
              :thumbnail "https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014"}
             {:id 4
              :name "image_name_big_long_name.jpg"
              :url "https://images.unsplash.com/photo-1423768164017-3f27c066407f?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=712b919f3a2f6fc34f29040e8082b6d9"
              :thumbnail "https://images.unsplash.com/photo-1423768164017-3f27c066407f?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=712b919f3a2f6fc34f29040e8082b6d9"}
             {:id 5
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1456428199391-a3b1cb5e93ab?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=765abd6dc931b7184e9795d8494966ed"
              :thumbnail "https://images.unsplash.com/photo-1456428199391-a3b1cb5e93ab?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=765abd6dc931b7184e9795d8494966ed"}
             {:id 6
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1447678523326-1360892abab8?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=91663afcd23da14f76cc8229c1780d47"
              :thumbnail "https://images.unsplash.com/photo-1447678523326-1360892abab8?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=91663afcd23da14f76cc8229c1780d47"}]}

   {:name "Generic 2"
    :id 2
    :builtin true
    :images [{:id 7
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1455098934982-64c622c5e066?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1400"
              :thumbnail "https://images.unsplash.com/photo-1455098934982-64c622c5e066?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1400"}
             {:id 8
              :name "image_name_long_name.jpg"
              :url "https://images.unsplash.com/photo-1422393462206-207b0fbd8d6b?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1925"
              :thumbnail "https://images.unsplash.com/photo-1422393462206-207b0fbd8d6b?crop=entropy&fit=crop&fm=jpg&h=1025&ixjsv=2.1.0&ixlib=rb-0.3.5&q=80&w=1925"}
             {:id 9
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014"
              :thumbnail "https://images.unsplash.com/photo-1441986380878-c4248f5b8b5b?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=486f09671860a11e70bdd0a45e7c5014"}
             {:id 10
              :name "image_name_big_long_name.jpg"
              :url "https://images.unsplash.com/photo-1423768164017-3f27c066407f?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=712b919f3a2f6fc34f29040e8082b6d9"
              :thumbnail "https://images.unsplash.com/photo-1423768164017-3f27c066407f?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=712b919f3a2f6fc34f29040e8082b6d9"}
             {:id 11
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1456428199391-a3b1cb5e93ab?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=765abd6dc931b7184e9795d8494966ed"
              :thumbnail "https://images.unsplash.com/photo-1456428199391-a3b1cb5e93ab?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=765abd6dc931b7184e9795d8494966ed"}
             {:id 12
              :name "image_name.jpg"
              :url "https://images.unsplash.com/photo-1447678523326-1360892abab8?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=91663afcd23da14f76cc8229c1780d47"
              :thumbnail "https://images.unsplash.com/photo-1447678523326-1360892abab8?ixlib=rb-0.3.5&q=80&fm=jpg&crop=entropy&w=1080&fit=max&s=91663afcd23da14f76cc8229c1780d47"}]}])
