import com.dns_technologies.mlkit_scanner.models.MLVisorRectFormer
import com.dns_technologies.mlkit_scanner.models.RecognizeVisorCropRect
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

// todo добавить тесты для метода prepareRect
class MLVisorRectFormerTest {
    lateinit var rectFormer: MLVisorRectFormer

    @Before
    fun init() {
       rectFormer = MLVisorRectFormer()
    }

    @Test
    fun correctUpdateWidthScale() {
        rectFormer.updateWidgetScales(widthScale = 0.7)
        assertTrue("Множитель ширины не был изменен", rectFormer.widgetWidthScale == 0.7)
        assertTrue("Множитель высоты изменился, хотя не должен был", rectFormer.widgetHeightScale == 1.0)
    }

    @Test
    fun correctUpdateWidthAndHeight() {
        rectFormer.updateWidgetScales(0.7, 0.7)
        assertTrue("Множитель ширины не был изменен", rectFormer.widgetWidthScale == 0.7)
        assertTrue("Множитель высоты не был изменен", rectFormer.widgetHeightScale == 0.7)
    }

    @Test
    fun correctShouldCropWithoutChanges() {
        assertTrue("Метод shouldCrop вернул true, когда в обрезке нет необходимости", !rectFormer.shouldCrop())
    }

    @Test
    fun correctShouldCropAfterChangeWidgetWidth() {
        rectFormer.updateWidgetScales(widthScale = 0.7)
        assertTrue("Метод shouldCrop вернул false, после изменения множителя ширины", rectFormer.shouldCrop())
    }

    @Test
    fun correctShouldCropAfterChangeRecognizeVisor() {
        rectFormer.recognizeCropRect = RecognizeVisorCropRect(scaleWidth = 0.7)
        assertTrue("Метод shouldCrop вернул false, после изменения размера визора", rectFormer.shouldCrop())
    }
}