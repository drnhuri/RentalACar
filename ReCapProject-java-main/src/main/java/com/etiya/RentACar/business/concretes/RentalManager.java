package com.etiya.RentACar.business.concretes;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.etiya.RentACar.business.abstracts.*;
import com.etiya.RentACar.business.constants.Messages;
import com.etiya.RentACar.business.dtos.*;
import com.etiya.RentACar.business.requests.Invoice.CreateInvoiceRequest;
import com.etiya.RentACar.business.requests.PosServiceRequest;
import com.etiya.RentACar.core.utilities.adapters.fakePos.PaymentByFakePosService;
import com.etiya.RentACar.core.utilities.adapters.fakePos.PaymentByFakePosServiceAdapter;
import com.etiya.RentACar.core.utilities.results.*;
import com.etiya.RentACar.entites.*;
import org.apache.tomcat.jni.Local;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.etiya.RentACar.business.requests.Rental.CreateRentalRequest;
import com.etiya.RentACar.business.requests.Rental.DeleteRentalRequest;
import com.etiya.RentACar.business.requests.Rental.UpdateRentalRequest;
import com.etiya.RentACar.core.utilities.business.BusinessRules;
import com.etiya.RentACar.core.utilities.mapping.ModelMapperService;
import com.etiya.RentACar.dataAccess.abstracts.RentalDao;
import com.etiya.RentACar.entites.ComplexTypes.RentalDetail;

@Service
public class RentalManager implements RentalService {

    private RentalDao rentalDao;
    private ModelMapperService modelMapperService;
    private CarService carService;
    private UserService userService;
    private InvoiceService invoiceService;
    private CityService cityService;
    private PaymentByFakePosService paymentByFakePosService;
    private RentalAdditionalService rentalAdditionalService;


    @Autowired
    private RentalManager(RentalDao rentalDao, ModelMapperService modelMapperService, CarService carService,
                          UserService userService, @Lazy InvoiceService invoiceService, @Lazy CityService cityService
            , PaymentByFakePosServiceAdapter paymentByFakePosService, RentalAdditionalService rentalAdditionalService) {
        super();
        this.rentalDao = rentalDao;
        this.modelMapperService = modelMapperService;
        this.carService = carService;
        this.userService = userService;
        this.invoiceService = invoiceService;
        this.rentalAdditionalService = rentalAdditionalService;
        this.cityService = cityService;
        this.paymentByFakePosService = paymentByFakePosService;
    }

    @Override
    public DataResult<List<RentalSearchListDto>> getAll() {
        List<Rental> result = this.rentalDao.findAll();
        List<RentalSearchListDto> response = result.stream()
                .map(rental -> modelMapperService.forDto().map(rental, RentalSearchListDto.class))
                .collect(Collectors.toList());
        return new SuccessDataResult<List<RentalSearchListDto>>(response, Messages.RENTALLIST);
    }

    @Override
    public DataResult<RentalSearchListDto> getByRentalId(int rentalId) {
        Result resultcheck = BusinessRules.run(checkIfRentalExists(rentalId));
        if (resultcheck!=null){
            return new ErrorDataResult(resultcheck);
        }

        Rental rental = this.rentalDao.getById(rentalId);
        RentalSearchListDto result = modelMapperService.forDto().map(rental, RentalSearchListDto.class);
        return new SuccessDataResult<RentalSearchListDto>(result, Messages.RENTALGET);
    }

    @Override
    public Result add(CreateRentalRequest createRentalRequest) {
        Result result = BusinessRules.run(checkIfCarExists(createRentalRequest.getCarId()),
                checkIfCarIsReturned(createRentalRequest.getCarId()),
                checkIfUserExists(createRentalRequest.getUserId()),
                checkIfCompareUserAndCarFindexScore(createRentalRequest.getUserId(),
                        createRentalRequest.getCarId()),
                checkIfCarIsMaintenance(createRentalRequest.getCarId())
                , checkIfCityExists(createRentalRequest.getRentCityId()));
        if (result != null) {
            return result;
        }

        Rental rental = modelMapperService.forRequest().map(createRentalRequest, Rental.class);

        List<AdditionalService> rentalAdditionalServices = getRentalAdditionals(createRentalRequest.getAdditionalServicesId()).getData();
        rental.setAdditionalServices(rentalAdditionalServices);

        rentalDao.save(rental);
        return new SuccessResult(Messages.RENTALADD);
    }

    @Override
    public Result update(UpdateRentalRequest updateRentalRequest) {
        Result resultCheck = BusinessRules.run(
                checkIfIsLimitEnough(updateRentalRequest.getRentalId(), updateRentalRequest.getReturnDate(),
                        updateRentalRequest.getCreditCard()),
                checkIfRentalExists(updateRentalRequest.getRentalId()),
                checkIsRentDateIsAfterThanReturnDate(updateRentalRequest.getRentalId(),updateRentalRequest.getReturnDate()),
                checkIfCityExists(updateRentalRequest.getReturnCityId()));
        if (resultCheck != null) {
            return resultCheck;
        }

        Rental result = modelMapperService.forRequest().map(updateRentalRequest, Rental.class);
        Rental response = this.rentalDao.getById(updateRentalRequest.getRentalId());
        response.setReturnDate(result.getReturnDate());
        response.setReturnCity(result.getReturnCity());
        response.setStartKm(response.getStartKm());
        response.setEndKm(updateRentalRequest.getEndKm());

        createInvoice(response);
        updateCarKm(response);

        this.rentalDao.save(response);
        return new SuccessResult(Messages.RENTALUPDATE);
    }

    @Override
    public Result delete(DeleteRentalRequest deleteRentalRequest) {
        Result result = BusinessRules.run(checkIfRentalExists(deleteRentalRequest.getRentalId()));
        if (result != null) {
            return result;
        }

        Rental rental = modelMapperService.forRequest().map(deleteRentalRequest, Rental.class);
        rentalDao.delete(rental);
        return new SuccessResult(Messages.RENTALDELETE);
    }

    @Override
    public Result checkIfCarIsReturned(int carId) {
        RentalDetail rental = this.rentalDao.getByCarIdWhereReturnDateIsNull(carId);
        if (rental != null) {
            return new ErrorResult(Messages.RENTALDATEERROR);
        }
        return new SuccessResult(Messages.RENTALDATESUCCESS);
    }

    private int totalRentDays(LocalDate rentDate, LocalDate returnDate) {
        Period period = Period.between(rentDate, returnDate);
        return period.getDays();
    }

    private void updateCarKm(Rental rental) {
        this.carService.updateCarKm(rental.getCar().getCarId(), rental.getEndKm());
    }

    private void createInvoice(Rental rental) {
        if ((rental.getRentCity().getCityId()) != (rental.getReturnCity().getCityId())) {
            updateInvoice(rental, 500, getRentalDailyTotalPrice(rental).getData());
            this.carService.updateCarCity(rental.getCar().getCarId(), rental.getReturnCity().getCityId());
        } else {
            updateInvoice(rental, 0, getRentalDailyTotalPrice(rental).getData());
            this.carService.updateCarCity(rental.getCar().getCarId(), rental.getReturnCity().getCityId());
        }
    }

    private DataResult<List<AdditionalService>> getRentalAdditionals(List<Integer> rentalAdditionalIds) {

        List<AdditionalService> rentalAdditionals = new ArrayList<AdditionalService>();

        for (int rentalAdditionalId : rentalAdditionalIds) {

            AdditionalService additionalService = new AdditionalService();
            additionalService.setServiceId(rentalAdditionalId);

            rentalAdditionals.add(additionalService);
        }

        return new SuccessDataResult<List<AdditionalService>>(rentalAdditionals);
    }

    private DataResult<Integer> getRentalAdditionalDailyPrice(AdditionalService rentalAdditionalId) {

        AdditionalService rentalAdditional = this.rentalAdditionalService.getById(rentalAdditionalId.getServiceId()).getData();
        int rentalAdditionalDailyPrice = rentalAdditional.getServiceDailyPrice();

        return new SuccessDataResult<Integer>(rentalAdditionalDailyPrice);
    }

    private DataResult<Integer> getRentalDailyTotalPrice(Rental rental) {
        int additionalDailyTotalPrice = 0;
        for (AdditionalService rentalAdditionalId : rental.getAdditionalServices()) {
            additionalDailyTotalPrice += this.getRentalAdditionalDailyPrice(rentalAdditionalId).getData();
        }
        return new SuccessDataResult<Integer>(additionalDailyTotalPrice);
    }

    private Result checkIfIsLimitEnough(int rentalId, LocalDate returnDate, CreditCardRentalDto creditCard) {



        Rental rental = this.rentalDao.getById(rentalId);

        CarSearchListDto car = this.carService.getById(rental.getCar().getCarId()).getData();
        double totalAmount = (car.getDailyPrice() * totalRentDays(rental.getRentDate(), returnDate));

        PosServiceRequest posServiceRequest = new PosServiceRequest();
        posServiceRequest.setTotalAmount(totalAmount);
        posServiceRequest.setCvv(creditCard.getCvv());
        posServiceRequest.setCreditCardNumber(creditCard.getCardNumber());

        if (!this.paymentByFakePosService.withdraw(posServiceRequest)) {
            return new ErrorResult(Messages.INSUFFICIENTBALANCE);
        }
        return new SuccessResult(Messages.SUFFICIENTBALANCE);
    }


    private Result checkIfCarIsMaintenance(int carId) {
        MaintenanceDto maintenanceDto = this.rentalDao.getByCarIdWhereMaintenanceReturnDateIsNull(carId);
        if (maintenanceDto != null) {
            return new ErrorResult(Messages.RENTALMAINTENANCEERROR);
        }
        return new SuccessResult(Messages.RENTALMAINTENANCE);
    }

    private Result checkIfCompareUserAndCarFindexScore(int userId, int carId) {
        DataResult<CarSearchListDto> car = this.carService.getById(carId);
        int user = this.userService.getById(userId).getData().getFindeksScore();
        if (car.getData().getMinFindeksScore() >= user) {
            return new ErrorResult(Messages.RENTALFINDEXSCOREERROR);
        }
        return new SuccessResult(Messages.RENTALFINDEXSCORE);
    }

    private Result checkIfUserExists(int userId) {
        if (!this.userService.checkIfUserExists(userId).isSuccess()) {
            return new ErrorResult(Messages.USERNOTFOUND);
        }
        return new SuccessResult(Messages.USERFOUND);
    }

    private Result checkIfRentalExists(int rentalId) {
        if (!this.rentalDao.existsById(rentalId)) {
            return new ErrorResult(Messages.RENTALNOTFOUND);
        }
        return new SuccessResult(Messages.RENTALGET);
    }

    private Result checkIfCarExists(int carId) {
        if (!this.carService.checkIfCarExists(carId).isSuccess()) {
            return new ErrorResult(Messages.CARNOTFOUND);
        }
        return new SuccessResult(Messages.CARFOUND);
    }


    private Result checkIfCityExists(int cityId) {
        if (!this.cityService.checkIfCityExists(cityId).isSuccess()) {
            return new ErrorResult(Messages.CITYNOTFOUND);
        }
        return new SuccessResult(Messages.CITYFOUND);
    }

    private Result updateInvoice(Rental rental, int extra, int additionalTotalPrice) {
        this.invoiceService.updateInvoiceIfReturnDateIsNotNull(rental, extra, additionalTotalPrice);
        return new SuccessResult(Messages.INVOICEUPDATE);
    }

    private Result checkIsRentDateIsAfterThanReturnDate(int rentalID , LocalDate returnDate){
        LocalDate rentDate = this.rentalDao.getById(rentalID).getRentDate();
        Period period = Period.between(rentDate, returnDate);
        if (period.getDays()<0){
            return new ErrorResult(Messages.RENTALDATEERROR);
        }
        return new SuccessResult();

    }

}
