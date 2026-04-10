Place your exported YOLOv8 TensorFlow Lite model here.

Required filename for current code:
- yolov8n_float32.tflite

Model expectations:
- Input: 1 x 640 x 640 x 3 (float32)
- Output: YOLOv8 detection tensor (supports [1,84,8400] and [1,8400,84])
- Class 0 must represent "person" (COCO order)
