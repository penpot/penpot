#include <cmath>
#include <iostream>

#include "Interpolation.h"
#include "Matrix2D.h"
#include "Vector2.h"
#include "Box2.h"

void resize()
{

}

int main(int argc, char** argv)
{
  Vector2<float> a(1, 0);
  Vector2<float> b(0, 1);

  Matrix2D<float> m;

  std::cout << m << std::endl;

  std::cout << m.rotate(M_PI / 2) << std::endl;

  Vector2<float> a2 = a * m;
  Vector2<float> b2 = b * m;

  std::cout << a << ", " << a2 << std::endl;
  std::cout << b << ", " << b2 << std::endl;

  std::cout << "Hello, World!" << std::endl;

  return 0;
}
