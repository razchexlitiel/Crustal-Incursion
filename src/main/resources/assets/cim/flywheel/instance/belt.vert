void flw_instanceVertex(in FlwInstance instance) {
    // 1. Умножаем позицию на матрицу трансформации
    flw_vertexPos = instance.pose * flw_vertexPos;

    // 2. ВАЖНО: Вычисляем нормаль математически, чтобы освещение работало правильно
    flw_vertexNormal = mat3(transpose(inverse(instance.pose))) * flw_vertexNormal;

    // 3. Базовые параметры рендера
    flw_vertexColor = instance.color * flw_vertexColor;
    flw_vertexLight = ivec2(instance.light);
    flw_vertexOverlay = ivec2(instance.overlay);

    // 4. Наш идеальный Уроборос (Сдвиг)
    flw_vertexTexCoord.y += instance.uvScroll;
}