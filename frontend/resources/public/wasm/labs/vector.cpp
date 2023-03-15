#include <emscripten.h>

#include <iostream>
#include <vector>
#include <memory>

enum ShapeType
{
  FRAME = 0,
  RECT,
  ELLIPSE,
};

struct Shape {
  ShapeType type;

  Shape() : type(FRAME) {};
};

static std::shared_ptr<Shape> root;

void EMSCRIPTEN_KEEPALIVE resize(const std::shared_ptr<Shape> ptr) 
{
  std::cout << "resize" << std::endl;
}
