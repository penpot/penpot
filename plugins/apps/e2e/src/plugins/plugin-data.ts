export default function () {
  const rectangle = penpot.createRectangle();

  rectangle?.setPluginData('testData', 'test');
  return rectangle?.getPluginData('testData');
}
