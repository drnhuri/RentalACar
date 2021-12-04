package com.etiya.RentACar.business.concretes;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.etiya.RentACar.business.abstracts.CarService;
import com.etiya.RentACar.business.abstracts.InvoiceService;
import com.etiya.RentACar.business.abstracts.RentalService;
import com.etiya.RentACar.business.abstracts.UserService;
import com.etiya.RentACar.business.constants.Messages;
import com.etiya.RentACar.business.dtos.CarImagesDto;
import com.etiya.RentACar.business.dtos.CarSearchListDto;
import com.etiya.RentACar.business.dtos.InvoiceSearchListDto;
import com.etiya.RentACar.business.dtos.RentalSearchListDto;
import com.etiya.RentACar.business.requests.Invoice.CreateInvoiceDateRequest;
import com.etiya.RentACar.business.requests.Invoice.CreateInvoiceRequest;
import com.etiya.RentACar.business.requests.Invoice.DeleteInvoiceRequest;
import com.etiya.RentACar.core.utilities.business.BusinessRules;
import com.etiya.RentACar.core.utilities.mapping.ModelMapperService;
import com.etiya.RentACar.core.utilities.results.*;
import com.etiya.RentACar.dataAccess.abstracts.InvoiceDao;
import com.etiya.RentACar.entites.Invoice;
import com.etiya.RentACar.entites.Rental;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.swing.plaf.nimbus.NimbusStyle;

@Service
public class InvoiceManager implements InvoiceService {
    private InvoiceDao invoiceDao;
    private ModelMapperService modelMapperService;
    private RentalService rentalService;
    private CarService carService;
    private UserService userService;

    @Autowired
    public InvoiceManager(InvoiceDao invoiceDao, ModelMapperService modelMapperService, @Lazy RentalService rentalService,
                          CarService carService, UserService userService) {
        super();
        this.invoiceDao = invoiceDao;
        this.modelMapperService = modelMapperService;
        this.rentalService = rentalService;
        this.userService = userService;
        this.carService = carService;
    }

    @Override
    public DataResult<List<InvoiceSearchListDto>> getAll() {
        List<Invoice> result = this.invoiceDao.findAll();
        List<InvoiceSearchListDto> response = result.stream().map(invoice -> modelMapperService.forDto().map(invoice, InvoiceSearchListDto.class)).collect(Collectors.toList());
        return new SuccessDataResult<List<InvoiceSearchListDto>>(response, Messages.INVOICELIST);
    }
    @Override
    public DataResult<List<InvoiceSearchListDto>> getRentingInvoiceByUserId(int userId) {
        Result resultCheck = BusinessRules.run(checkIfUserExists(userId),//1ındu 1 corparate      3 inovice yok
                checkIsThereInvoiceOfUser(userId));
        if (resultCheck != null) {
            return new ErrorDataResult(resultCheck);
        }
        List<Invoice> result = invoiceDao.getByRental_User_Id(userId);
        List<InvoiceSearchListDto> response = result.stream()
                .map(invoice -> modelMapperService.forDto()
                        .map(invoice, InvoiceSearchListDto.class)).collect(Collectors.toList());
        return new SuccessDataResult<List<InvoiceSearchListDto>>(response,Messages.INVOICEGET);
    }

    @Override
    public Result add(CreateInvoiceRequest createInvoiceRequest) {
        Result result = BusinessRules.run(
                checkIfRentalExists(createInvoiceRequest.getRentalId()));
        if (result != null) {
            return result;
        }

        RentalSearchListDto rental = rentalService.getByRentalId(createInvoiceRequest.getRentalId()).getData();

        CarSearchListDto car = this.carService.getById(rental.getCarId()).getData();

        LocalDate returnDateCount = rental.getReturnDate();
        LocalDate rentDateCount = rental.getRentDate();
        Period period = Period.between(rentDateCount, returnDateCount);
        int days = period.getDays();
        double totalAmount = car.getDailyPrice() * days;

        Invoice invoice = modelMapperService.forRequest().map(createInvoiceRequest, Invoice.class);
        invoice.setTotalRentalDay(days);
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setTotalAmount(totalAmount);
        invoice.setInvoiceNumber(createInvoiceNumber(rental.getRentalId()).getData());
        this.invoiceDao.save(invoice);
        return new SuccessResult(Messages.INVOICEADD);
    }

    @Override
    public Result delete(DeleteInvoiceRequest deleteRentalRequest) {

        Result result = BusinessRules.run(checkIfInvoiceExists(deleteRentalRequest.getInvoiceId()));
        if (result!=null){
            return result;
        }

        Invoice invoice = modelMapperService.forDto().map(deleteRentalRequest, Invoice.class);
        this.invoiceDao.delete(invoice);
        return new SuccessResult(Messages.INVOICEDELETE);
    }



    @Override
    public DataResult<List<InvoiceSearchListDto>> getByCreateDateBetweenBeginDateAndEndDate(LocalDate beginDate, LocalDate endDate) {
        List<Invoice> invoices = this.invoiceDao.findByInvoiceDateBetween(beginDate, endDate);
        List<InvoiceSearchListDto> invoiceSearchListDtos = invoices.stream()
                .map(invoice -> modelMapperService.forDto().map(invoice, InvoiceSearchListDto.class)).collect(Collectors.toList());
        return new SuccessDataResult<List<InvoiceSearchListDto>>(invoiceSearchListDtos,Messages.INVOICEBYCUSTOMERLIST);
    }

    @Override
    public Result updateInvoiceIfReturnDateIsNotNull(Rental rental, int extra, int addtionalTotalPrice) {
        CarSearchListDto car = this.carService.getById(rental.getCar().getCarId()).getData();

        int days = totalRentDays(rental).getData();
        double totalAmount = (car.getDailyPrice() * days) + extra + (addtionalTotalPrice * days);

        Invoice invoice = modelMapperService.forRequest().map(rental, Invoice.class);
        invoice.setTotalRentalDay(days);
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setTotalAmount(totalAmount);
        invoice.setInvoiceNumber(createInvoiceNumber(rental.getRentalId()).getData());
        this.invoiceDao.save(invoice);
        return new SuccessResult(Messages.INVOICEADD);
    }

    private DataResult<String> createInvoiceNumber(int rentalId) {
        LocalDate now = LocalDate.now();
        String currentYear = String.valueOf(now.getYear());
        String currentMonth = String.valueOf(now.getMonthValue());
        String rentalIdS = String.valueOf(rentalId);
        String invoiceNumber = currentYear + currentMonth + "-" + rentalIdS;
        return new SuccessDataResult<>(invoiceNumber);
    }

    private DataResult<Integer> totalRentDays(Rental rental) {
        Period period = Period.between(rental.getRentDate(), rental.getReturnDate());
        return new SuccessDataResult<Integer>(period.getDays());
    }

    private Result checkIfRentalExists(int rentalId) {

        if (this.invoiceDao.existsByRental_RentalId(rentalId)) {
            return new ErrorResult(Messages.INVOICENOTADD);
        }
        return new SuccessResult();
    }

    private Result checkIsThereInvoiceOfUser(int userId){
        if (!this.invoiceDao.existsByRental_UserId(userId)){
            return new ErrorResult(Messages.INVOICEUSERROR);
        }
        return new SuccessResult();
    }

    private Result checkIfInvoiceExists(int invoiceId){
        if (!this.invoiceDao.existsById(invoiceId)){
            return new ErrorResult(Messages.INVOICENOTFOUND);
        }
        return new SuccessResult();
    }

    private Result checkIfUserExists(int userId) {
        if (!userService.checkIfUserExists(userId).isSuccess()) {
            return new ErrorResult(Messages.USERNOTFOUND);
        }
        return new SuccessResult(Messages.USERFOUND);
    }

}
